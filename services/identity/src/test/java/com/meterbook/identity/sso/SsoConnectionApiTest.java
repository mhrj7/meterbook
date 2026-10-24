package com.meterbook.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties =
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://login.meterbook.test/realms/meterbook")
@AutoConfigureMockMvc
@Import({TestDatabase.class, SsoConnectionApiTest.Fixture.class})
class SsoConnectionApiTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  private static final String ISSUER = "https://login.meterbook.test/realms/meterbook";

  @Autowired MockMvc mvc;
  @Autowired FakeDirectory directory;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;

  private UUID tenant;
  private CallerAuthentication admin;

  @BeforeEach
  void tenant() {
    directory.reset();
    tenant = newTenant();
    admin = TestCallers.tenantUser(UUID.randomUUID(), tenant, Role.TENANT_ADMIN);
  }

  private UUID newTenant() {
    UUID id = UUID.randomUUID();
    scope.inTenant(
        id,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, id)
                .set(Tables.TENANTS.SLUG, "t-" + id.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Tenant")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    return id;
  }

  private static String body(String ssoUrl, String certificate) {
    return """
        {"display_name":"Acme Okta","protocol":"saml","saml":{"entity_id":"http://www.okta.com/exk1","sso_url":"%s","signing_certificate":%s}}"""
        .formatted(ssoUrl, JsonMapper.builder().build().writeValueAsString(certificate));
  }

  private static String validBody() {
    return body("https://acme.okta.com/app/sso/saml", SigningCertificateTest.pem("idp-valid.crt"));
  }

  private ResultActions create(CallerAuthentication caller, String key, String body)
      throws Exception {
    return mvc.perform(
        post("/v1/sso-connections")
            .with(authentication(caller))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  private UUID createValid() throws Exception {
    String response =
        create(admin, UUID.randomUUID().toString(), validBody())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asString());
  }

  @Test
  void a_tenant_admin_connects_their_saml_idp() throws Exception {
    String response =
        create(admin, "k", validBody())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.protocol").value("saml"))
            .andExpect(jsonPath("$.display_name").value("Acme Okta"))
            .andExpect(jsonPath("$.saml.entity_id").value("http://www.okta.com/exk1"))
            .andExpect(jsonPath("$.saml.signing_certificate").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json.readTree(response).get("id").asString());
    String alias = "sso-" + id;

    mvc.perform(get("/v1/sso-connections/" + id).with(authentication(admin)))
        .andExpect(jsonPath("$.service_provider.entity_id").value(ISSUER + "/sso/" + id))
        .andExpect(
            jsonPath("$.service_provider.acs_url").value(ISSUER + "/broker/" + alias + "/endpoint"))
        .andExpect(
            jsonPath("$.service_provider.metadata_url")
                .value(ISSUER + "/broker/" + alias + "/endpoint/descriptor"));

    Directory.SamlProvider provider = directory.providers.get(alias);
    assertThat(provider.serviceProviderEntityId()).isEqualTo(ISSUER + "/sso/" + id);
    assertThat(provider.signingCertificate()).doesNotContain("BEGIN", "\n");
    assertThat(directory.providerOrganizations.get(alias)).isEqualTo(tenant);
  }

  @Test
  void one_connection_per_tenant_and_retries_are_idempotent() throws Exception {
    create(admin, "same", validBody()).andExpect(status().isCreated());
    create(admin, "same", validBody())
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    create(admin, "other", validBody())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("sso_connection_exists"));
    assertThat(directory.calls("createSamlProvider")).isEqualTo(1);
  }

  @Test
  void connections_are_invisible_to_other_tenants() throws Exception {
    UUID id = createValid();
    CallerAuthentication otherAdmin =
        TestCallers.tenantUser(UUID.randomUUID(), newTenant(), Role.TENANT_ADMIN);

    mvc.perform(get("/v1/sso-connections/" + id).with(authentication(otherAdmin)))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/v1/sso-connections/" + id).with(authentication(otherAdmin)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v1/sso-connections").with(authentication(otherAdmin)))
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(get("/v1/sso-connections").with(authentication(admin)))
        .andExpect(jsonPath("$.data[0].id").value(id.toString()))
        .andExpect(jsonPath("$.has_more").value(false));
    assertThat(directory.providers).hasSize(1);
  }

  @Test
  void only_tenant_admins_manage_connections() throws Exception {
    CallerAuthentication member = TestCallers.tenantUser(UUID.randomUUID(), tenant);
    create(member, "k", validBody())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_admin_required"));
    mvc.perform(get("/v1/sso-connections").with(authentication(member)))
        .andExpect(status().isForbidden());
    create(TestCallers.platformAdmin(UUID.randomUUID()), "k2", validBody())
        .andExpect(status().isForbidden());
  }

  @Test
  void rejects_bad_certificates_and_plain_http() throws Exception {
    create(admin, "c1", body("https://idp.test/sso", SigningCertificateTest.pem("idp-expired.crt")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("certificate_expired"))
        .andExpect(jsonPath("$.error.param").value("saml.signing_certificate"));
    create(admin, "c2", body("https://idp.test/sso", "x".repeat(200)))
        .andExpect(jsonPath("$.error.code").value("certificate_malformed"));
    create(admin, "c3", body("http://idp.test/sso", SigningCertificateTest.pem("idp-valid.crt")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("sso_url_not_https"));
    create(admin, "c4", body("ftp://idp.test/sso", SigningCertificateTest.pem("idp-valid.crt")))
        .andExpect(jsonPath("$.error.param").value("saml.sso_url"));
    assertThat(directory.calls("createSamlProvider")).isZero();
  }

  @Test
  void a_keycloak_outage_leaves_nothing_behind() throws Exception {
    directory.failOn("createSamlProvider", new Directory.UnavailableException("down", null));

    create(admin, "outage", validBody())
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "2"));

    assertThat(directory.providers).isEmpty();
    assertThat(directory.calls("deleteIdentityProvider")).isEqualTo(1);
    assertThat(
            scope.inTenant(
                tenant,
                () ->
                    db.fetchExists(
                        Tables.SSO_CONNECTIONS, Tables.SSO_CONNECTIONS.TENANT_ID.eq(tenant))))
        .isFalse();
  }

  @Test
  void a_database_conflict_after_keycloak_changed_removes_the_provider() throws Exception {
    directory.onCall(
        "createSamlProvider",
        () ->
            scope.inTenant(
                tenant,
                () ->
                    db.insertInto(Tables.SSO_CONNECTIONS)
                        .set(Tables.SSO_CONNECTIONS.ID, UUID.randomUUID())
                        .set(Tables.SSO_CONNECTIONS.TENANT_ID, tenant)
                        .set(
                            Tables.SSO_CONNECTIONS.KEYCLOAK_ALIAS, "sso-racer-" + UUID.randomUUID())
                        .set(Tables.SSO_CONNECTIONS.PROTOCOL, "saml")
                        .set(Tables.SSO_CONNECTIONS.DISPLAY_NAME, "racer")
                        .set(Tables.SSO_CONNECTIONS.IDP_ENTITY_ID, "x")
                        .set(Tables.SSO_CONNECTIONS.SSO_URL, "https://x")
                        .set(Tables.SSO_CONNECTIONS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .execute()));

    create(admin, "race", validBody()).andExpect(status().isConflict());
    assertThat(directory.providers).isEmpty();
  }

  @Test
  void deleting_removes_the_provider_first() throws Exception {
    UUID id = createValid();

    mvc.perform(delete("/v1/sso-connections/" + id).with(authentication(admin)))
        .andExpect(status().isNoContent());
    assertThat(directory.providers).isEmpty();
    mvc.perform(delete("/v1/sso-connections/" + id).with(authentication(admin)))
        .andExpect(status().isNotFound());
    create(admin, "again", validBody()).andExpect(status().isCreated());
  }

  @Test
  void a_delete_during_an_outage_keeps_the_connection() throws Exception {
    UUID id = createValid();
    directory.failOn("deleteIdentityProvider", new Directory.UnavailableException("down", null));

    mvc.perform(delete("/v1/sso-connections/" + id).with(authentication(admin)))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/v1/sso-connections/" + id).with(authentication(admin)))
        .andExpect(status().isOk());
  }

  @Test
  void validates_paging_parameters() throws Exception {
    mvc.perform(get("/v1/sso-connections?limit=0").with(authentication(admin)))
        .andExpect(jsonPath("$.error.param").value("limit"));
    mvc.perform(get("/v1/sso-connections?limit=201").with(authentication(admin)))
        .andExpect(status().isUnprocessableContent());
    mvc.perform(get("/v1/sso-connections?starting_after=nope").with(authentication(admin)))
        .andExpect(jsonPath("$.error.code").value("cursor_invalid"));
    mvc.perform(
            get("/v1/sso-connections?limit=1&starting_after=" + new UUID(0, 0))
                .with(authentication(admin)))
        .andExpect(status().isOk());
  }

  @Test
  void an_unexpected_directory_error_is_a_500() throws Exception {
    directory.failOn(
        "createSamlProvider", new IllegalStateException("Keycloak rejected the request"));

    create(admin, "bug", validBody()).andExpect(status().isInternalServerError());
    assertThat(directory.calls("deleteIdentityProvider")).isZero();
  }

  @Test
  void plain_http_idps_are_allowed_only_when_configured() {
    var strict =
        new SsoConnectionService(null, null, null, null, new SsoProperties(ISSUER, false), null);
    var lenient =
        new SsoConnectionService(null, null, null, null, new SsoProperties(ISSUER, true), null);

    lenient.requireAllowedUrl("http://localhost:8180/realms/acme-idp/protocol/saml");
    lenient.requireAllowedUrl("https://idp.test/sso");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> strict.requireAllowedUrl("http://idp.test/sso"))
        .isInstanceOf(com.meterbook.identity.web.ApiException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> lenient.requireAllowedUrl("ftp://idp.test/sso"))
        .isInstanceOf(com.meterbook.identity.web.ApiException.class);
  }
}
