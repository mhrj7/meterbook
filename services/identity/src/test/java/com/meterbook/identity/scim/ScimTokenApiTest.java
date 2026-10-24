package com.meterbook.identity.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.MutableClock;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, ScimTokenApiTest.Fixture.class})
class ScimTokenApiTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;
  @Autowired MutableClock clock;

  private UUID tenant;
  private CallerAuthentication admin;

  @BeforeEach
  void tenant() {
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

  private OffsetDateTime lastUsed(UUID id) {
    return scope.inTenant(
        tenant,
        () ->
            db.select(Tables.SCIM_TOKENS.LAST_USED_AT)
                .from(Tables.SCIM_TOKENS)
                .where(Tables.SCIM_TOKENS.ID.eq(id))
                .fetchOne()
                .value1());
  }

  private ResultActions issue(CallerAuthentication caller, String key) throws Exception {
    return mvc.perform(
        post("/v1/scim-tokens")
            .with(authentication(caller))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content("{\"description\":\"Okta provisioning\"}"));
  }

  private JsonNode issued() throws Exception {
    return json.readTree(
        issue(admin, UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString());
  }

  private ResultActions scim(String token) throws Exception {
    var request = get("/scim/v2/ServiceProviderConfig");
    return mvc.perform(
        token == null ? request : request.header("Authorization", "Bearer " + token));
  }

  @Test
  void issues_a_token_whose_secret_is_shown_once_and_stored_only_as_a_hash() throws Exception {
    JsonNode token = issued();
    String secret = token.get("secret").asString();

    assertThat(secret).matches("mbscim_[0-9A-Za-z]{43}");
    assertThat(token.get("prefix").asString()).isEqualTo(secret.substring(0, 12));
    var row =
        scope.inTenant(
            tenant,
            () ->
                db.selectFrom(Tables.SCIM_TOKENS)
                    .where(Tables.SCIM_TOKENS.ID.eq(UUID.fromString(token.get("id").asString())))
                    .fetchOne());
    assertThat(row.getTokenHash()).isEqualTo(ScimTokens.hash(secret)).doesNotContain(secret);
    assertThat(
            db.fetch(
                "select response_body from idempotency_keys where response_body like ?",
                "%" + secret + "%"))
        .isEmpty();
  }

  @Test
  void repeating_the_request_does_not_reveal_the_secret_again() throws Exception {
    String location =
        issue(admin, "same")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    issue(admin, "same")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_used"))
        .andExpect(
            jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString(location)))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mbscim_"))));
  }

  @Test
  void lists_live_tokens_without_secrets() throws Exception {
    JsonNode token = issued();
    String id = token.get("id").asString();

    mvc.perform(get("/v1/scim-tokens").with(authentication(admin)))
        .andExpect(jsonPath("$.data[0].id").value(id))
        .andExpect(jsonPath("$.data[0].secret").doesNotExist())
        .andExpect(jsonPath("$.data[0].last_used_at").doesNotExist())
        .andExpect(jsonPath("$.has_more").value(false));

    mvc.perform(delete("/v1/scim-tokens/" + id).with(authentication(admin)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/scim-tokens").with(authentication(admin)))
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(delete("/v1/scim-tokens/" + id).with(authentication(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void tokens_are_invisible_to_other_tenants_and_members() throws Exception {
    String id = issued().get("id").asString();
    CallerAuthentication otherAdmin =
        TestCallers.tenantUser(UUID.randomUUID(), newTenant(), Role.TENANT_ADMIN);

    mvc.perform(delete("/v1/scim-tokens/" + id).with(authentication(otherAdmin)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v1/scim-tokens").with(authentication(otherAdmin)))
        .andExpect(jsonPath("$.data").isEmpty());
    issue(TestCallers.tenantUser(UUID.randomUUID(), tenant), "m").andExpect(status().isForbidden());
    issue(TestCallers.platformAdmin(UUID.randomUUID()), "p").andExpect(status().isForbidden());
  }

  @Test
  void validates_paging() throws Exception {
    mvc.perform(get("/v1/scim-tokens?limit=500").with(authentication(admin)))
        .andExpect(jsonPath("$.error.param").value("limit"));
    mvc.perform(get("/v1/scim-tokens?starting_after=x").with(authentication(admin)))
        .andExpect(jsonPath("$.error.code").value("cursor_invalid"));
    issued();
    issued();
    mvc.perform(get("/v1/scim-tokens?limit=1").with(authentication(admin)))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.has_more").value(true))
        .andExpect(jsonPath("$.next_cursor").isString());
  }

  @Test
  void a_live_token_authenticates_scim_requests() throws Exception {
    String secret = issued().get("secret").asString();

    scim(secret)
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Type", org.hamcrest.Matchers.startsWith("application/scim+json")))
        .andExpect(jsonPath("$.patch.supported").value(true))
        .andExpect(jsonPath("$.filter.maxResults").value(200));
    mvc.perform(get("/scim/v2/ResourceTypes").header("Authorization", "Bearer " + secret))
        .andExpect(jsonPath("$.totalResults").value(2))
        .andExpect(jsonPath("$.Resources[0].endpoint").value("/Users"));
    mvc.perform(get("/scim/v2/Schemas").header("Authorization", "Bearer " + secret))
        .andExpect(
            jsonPath("$.Resources[1].id").value("urn:ietf:params:scim:schemas:core:2.0:Group"));
  }

  @Test
  void anything_but_a_live_token_is_rejected_in_scim_format() throws Exception {
    scim(null)
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"meterbook-scim\""))
        .andExpect(
            header()
                .string("Content-Type", org.hamcrest.Matchers.startsWith("application/scim+json")))
        .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
        .andExpect(jsonPath("$.status").value("401"));
    scim("mbscim_" + "a".repeat(43)).andExpect(status().isUnauthorized());
    scim("mbscim_short").andExpect(status().isUnauthorized());
    scim("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln").andExpect(status().isUnauthorized());
    mvc.perform(get("/scim/v2/ServiceProviderConfig").header("Authorization", "Basic dXNlcjpwYXNz"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_revoked_token_stops_working_on_the_next_request() throws Exception {
    JsonNode token = issued();
    String secret = token.get("secret").asString();
    scim(secret).andExpect(status().isOk());

    mvc.perform(delete("/v1/scim-tokens/" + token.get("id").asString()).with(authentication(admin)))
        .andExpect(status().isNoContent());

    scim(secret).andExpect(status().isUnauthorized());
  }

  @Test
  void records_when_a_token_was_last_used_at_most_once_a_minute() throws Exception {
    JsonNode token = issued();
    UUID id = UUID.fromString(token.get("id").asString());
    String secret = token.get("secret").asString();

    scim(secret);
    OffsetDateTime first = lastUsed(id);
    clock.advance(Duration.ofSeconds(30));
    scim(secret);
    OffsetDateTime unchanged = lastUsed(id);
    clock.advance(Duration.ofMinutes(2));
    scim(secret);
    OffsetDateTime later = lastUsed(id);

    assertThat(first).isNotNull();
    assertThat(unchanged).isEqualTo(first);
    assertThat(later).isAfter(first);
  }

  @Test
  void scim_authentications_compare_by_tenant_and_token() {
    UUID t = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    assertThat(new ScimAuthentication(t, k))
        .isEqualTo(new ScimAuthentication(t, k))
        .hasSameHashCodeAs(new ScimAuthentication(t, k));
    assertThat(new ScimAuthentication(t, k))
        .isNotEqualTo(new ScimAuthentication(t, UUID.randomUUID()))
        .isNotEqualTo("x");
    assertThat(new ScimAuthentication(t, k).getCredentials()).isEqualTo("");
  }
}
