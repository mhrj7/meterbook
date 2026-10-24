package com.meterbook.identity.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TestDatabase;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, ScimUsersApiTest.Fixture.class})
class ScimUsersApiTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  private static final String SCIM = "application/scim+json";

  @Autowired MockMvc mvc;
  @Autowired FakeDirectory directory;
  @Autowired DSLContext db;
  @Autowired ScimTokens tokens;
  @Autowired JsonMapper json;

  private ScimTestSupport acme;

  @BeforeEach
  void setUp() {
    directory.reset();
    acme = ScimTestSupport.tenant(db, tokens);
  }

  private MockHttpServletRequestBuilder as(
      ScimTestSupport tenant, MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + tenant.token());
  }

  private ResultActions createUser(ScimTestSupport tenant, String local) throws Exception {
    return mvc.perform(
        as(tenant, post("/scim/v2/Users")).contentType(SCIM).content(tenant.user(local)));
  }

  private String createdId(String local) throws Exception {
    return json.readTree(createUser(acme, local).andReturn().getResponse().getContentAsString())
        .get("id")
        .asString();
  }

  private ResultActions patchUser(String id, String body, String ifMatch) throws Exception {
    var request = as(acme, patch("/scim/v2/Users/" + id)).contentType(SCIM).content(body);
    return mvc.perform(ifMatch == null ? request : request.header("If-Match", ifMatch));
  }

  private static String op(String op, String path, String value) {
    String pathPart = path == null ? "" : ",\"path\":\"" + path + "\"";
    String valuePart = value == null ? "" : ",\"value\":" + value;
    return "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\""
        + op
        + "\""
        + pathPart
        + valuePart
        + "}]}";
  }

  private UUID keycloakUserOf(String id) {
    return db.select(Tables.SCIM_USERS.KEYCLOAK_USER_ID)
        .from(Tables.SCIM_USERS)
        .where(Tables.SCIM_USERS.ID.eq(UUID.fromString(id)))
        .fetchOne()
        .value1();
  }

  @Test
  void provisions_a_user_into_keycloak_as_a_member_of_the_tenant() throws Exception {
    String body =
        createUser(acme, "jane")
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "W/\"1\""))
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(SCIM)))
            .andExpect(jsonPath("$.userName").value(acme.email("jane")))
            .andExpect(jsonPath("$.externalId").value("ext-jane"))
            .andExpect(jsonPath("$.name.givenName").value("Jane"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.emails[0].value").value(acme.email("jane")))
            .andExpect(jsonPath("$.meta.resourceType").value("User"))
            .andExpect(jsonPath("$.meta.version").value("W/\"1\""))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(body).get("id").asString();
    UUID kc = keycloakUserOf(id);

    assertThat(directory.users.get(kc)).isEqualTo(acme.email("jane"));
    assertThat(directory.members.get(kc)).containsExactly(acme.tenant());
    assertThat(directory.enabled.get(kc)).isTrue();
    assertThat(directory.links).isEmpty();
  }

  @Test
  void only_emails_in_the_tenants_domains_are_accepted() throws Exception {
    String foreign = acme.user("x").replace(acme.domain(), "globex.test");

    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content(foreign))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidValue"))
        .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"));
    assertThat(directory.users).isEmpty();
  }

  @Test
  void user_names_are_unique_per_tenant_ignoring_case() throws Exception {
    createUser(acme, "jane").andExpect(status().isCreated());
    String upper =
        acme.user("jane")
            .replace(
                "\"userName\":\"" + acme.email("jane"), "\"userName\":\"" + acme.email("JANE"));

    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content(upper))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.scimType").value("uniqueness"));
  }

  @Test
  void never_takes_over_a_keycloak_user_of_another_tenant() throws Exception {
    UUID other = directory.createUser(acme.email("jane"), "", "");
    directory.addMember(UUID.randomUUID(), other);

    createUser(acme, "jane")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.scimType").value("uniqueness"));
    assertThat(directory.members.get(other)).doesNotContain(acme.tenant());
  }

  @Test
  void adopts_a_keycloak_user_that_belongs_to_no_tenant_or_already_to_this_one() throws Exception {
    UUID loose = directory.createUser(acme.email("jane"), "", "");
    UUID jit = directory.createUser(acme.email("joe"), "", "");
    directory.addMember(acme.tenant(), jit);

    createUser(acme, "jane").andExpect(status().isCreated());
    createUser(acme, "joe").andExpect(status().isCreated());

    assertThat(directory.calls("createUser")).isEqualTo(2); // only the two set up above
    assertThat(directory.members.get(loose)).containsExactly(acme.tenant());
    assertThat(directory.calls("addMember")).isEqualTo(2); // jit's membership + loose's new one
  }

  @Test
  void links_users_to_the_tenants_sso_connection() throws Exception {
    String alias = "sso-" + UUID.randomUUID();
    db.insertInto(Tables.SSO_CONNECTIONS)
        .set(Tables.SSO_CONNECTIONS.ID, UUID.randomUUID())
        .set(Tables.SSO_CONNECTIONS.TENANT_ID, acme.tenant())
        .set(Tables.SSO_CONNECTIONS.KEYCLOAK_ALIAS, alias)
        .set(Tables.SSO_CONNECTIONS.PROTOCOL, "saml")
        .set(Tables.SSO_CONNECTIONS.DISPLAY_NAME, "Okta")
        .set(Tables.SSO_CONNECTIONS.IDP_ENTITY_ID, "x")
        .set(Tables.SSO_CONNECTIONS.SSO_URL, "https://x")
        .set(Tables.SSO_CONNECTIONS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute();

    String id = createdId("jane");

    assertThat(directory.links.get(keycloakUserOf(id))).containsEntry(alias, acme.email("jane"));
  }

  @Test
  void users_of_one_tenant_are_invisible_to_another() throws Exception {
    String id = createdId("jane");
    ScimTestSupport globex = ScimTestSupport.tenant(db, tokens);

    mvc.perform(as(globex, get("/scim/v2/Users/" + id)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("404"));
    mvc.perform(as(globex, delete("/scim/v2/Users/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(globex, get("/scim/v2/Users"))).andExpect(jsonPath("$.totalResults").value(0));
    patchUserAs(globex, id).andExpect(status().isNotFound());
  }

  private ResultActions patchUserAs(ScimTestSupport tenant, String id) throws Exception {
    return mvc.perform(
        as(tenant, patch("/scim/v2/Users/" + id))
            .contentType(SCIM)
            .content(op("replace", "active", "false")));
  }

  @Test
  void finds_users_with_the_filters_scim_clients_use() throws Exception {
    String id = createdId("jane");
    createdId("joe");

    mvc.perform(
            as(
                acme,
                get("/scim/v2/Users")
                    .param("filter", "userName eq \"" + acme.email("JANE") + "\"")))
        .andExpect(jsonPath("$.totalResults").value(1))
        .andExpect(jsonPath("$.Resources[0].id").value(id))
        .andExpect(
            jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
    mvc.perform(as(acme, get("/scim/v2/Users").param("filter", "externalId eq \"ext-joe\"")))
        .andExpect(jsonPath("$.totalResults").value(1));
    mvc.perform(as(acme, get("/scim/v2/Users").param("startIndex", "2").param("count", "1")))
        .andExpect(jsonPath("$.totalResults").value(2))
        .andExpect(jsonPath("$.startIndex").value(2))
        .andExpect(jsonPath("$.itemsPerPage").value(1));
    mvc.perform(as(acme, get("/scim/v2/Users").param("filter", "userName sw \"j\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidFilter"));
    mvc.perform(as(acme, get("/scim/v2/Users").param("filter", "title eq \"x\"")))
        .andExpect(jsonPath("$.scimType").value("invalidFilter"));
  }

  @Test
  void deactivating_disables_the_user_and_ends_their_sessions_before_responding() throws Exception {
    String id = createdId("jane");
    UUID kc = keycloakUserOf(id);

    // Entra ID sends booleans as strings.
    patchUser(id, op("Replace", "active", "\"False\""), "W/\"1\"")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(header().string("ETag", "W/\"2\""));

    assertThat(directory.enabled.get(kc)).isFalse();
    assertThat(directory.logouts.get(kc)).isEqualTo(1);

    // Okta sends the value object without a path.
    patchUser(id, op("replace", null, "{\"active\":true}"), null)
        .andExpect(jsonPath("$.active").value(true));
    assertThat(directory.enabled.get(kc)).isTrue();
    assertThat(directory.logouts.get(kc)).isEqualTo(1);
  }

  @Test
  void patches_names_and_emails_and_relinks_a_changed_email() throws Exception {
    String id = createdId("jane");
    UUID kc = keycloakUserOf(id);

    patchUser(id, op("replace", "name.givenName", "\"Janet\""), null)
        .andExpect(jsonPath("$.name.givenName").value("Janet"));
    patchUser(
            id,
            op("replace", "emails[type eq \\\"work\\\"].value", "\"" + acme.email("janet") + "\""),
            null)
        .andExpect(jsonPath("$.emails[0].value").value(acme.email("janet")));
    patchUser(id, op("remove", "externalId", null), null)
        .andExpect(jsonPath("$.externalId").doesNotExist());

    assertThat(directory.profiles.get(kc)).containsExactly(acme.email("janet"), "Janet", "Doe");
  }

  @Test
  void rejects_invalid_patches() throws Exception {
    String id = createdId("jane");

    patchUser(id, op("replace", "title", "\"CEO\""), null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidPath"));
    patchUser(id, op("remove", "userName", null), null)
        .andExpect(jsonPath("$.scimType").value("mutability"));
    patchUser(id, op("frobnicate", "active", "true"), null)
        .andExpect(jsonPath("$.scimType").value("invalidSyntax"));
    patchUser(id, "{\"Operations\":[]}", null)
        .andExpect(jsonPath("$.scimType").value("invalidSyntax"));
    patchUser(id, op("replace", "active", "\"maybe\""), null)
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    patchUser(id, op("replace", "emails[type eq \\\"work\\\"].value", "\"x@globex.test\""), null)
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    patchUser(
            id,
            op(
                "replace",
                "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department",
                "\"Ops\""),
            null)
        .andExpect(status().isOk());
  }

  @Test
  void if_match_protects_against_lost_updates() throws Exception {
    String id = createdId("jane");

    patchUser(id, op("replace", "displayName", "\"Jane D\""), "W/\"7\"")
        .andExpect(status().isPreconditionFailed());
    patchUser(id, op("replace", "displayName", "\"Jane D\""), "W/\"1\"").andExpect(status().isOk());
    patchUser(id, op("replace", "displayName", "\"Jane E\""), "W/\"1\"")
        .andExpect(status().isPreconditionFailed());
  }

  @Test
  void replaces_a_user_with_put() throws Exception {
    String id = createdId("jane");
    String replacement =
        acme.user("jane")
            .replace("\"Jane\"", "\"J\"")
            .replace("\"active\":true", "\"active\":false");

    mvc.perform(as(acme, put("/scim/v2/Users/" + id)).contentType(SCIM).content(replacement))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name.givenName").value("J"))
        .andExpect(jsonPath("$.active").value(false));
    assertThat(directory.enabled.get(keycloakUserOf(id))).isFalse();
  }

  @Test
  void an_email_already_used_by_another_user_is_a_conflict() throws Exception {
    String id = createdId("jane");
    createdId("joe");

    patchUser(
            id,
            op("replace", "emails[type eq \\\"work\\\"].value", "\"" + acme.email("joe") + "\""),
            null)
        .andExpect(status().isConflict());
  }

  @Test
  void a_keycloak_outage_during_create_leaves_nothing_behind() throws Exception {
    directory.failOn("setEnabled", new Directory.UnavailableException("down", null));

    createUser(acme, "jane")
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "2"));

    assertThat(directory.users).isEmpty();
    assertThat(db.fetchCount(Tables.SCIM_USERS, Tables.SCIM_USERS.TENANT_ID.eq(acme.tenant())))
        .isZero();
  }

  @Test
  void a_keycloak_outage_during_deactivation_changes_nothing() throws Exception {
    String id = createdId("jane");
    directory.failOn("logout", new Directory.UnavailableException("down", null));

    patchUser(id, op("replace", "active", "false"), null)
        .andExpect(status().isServiceUnavailable());

    mvc.perform(as(acme, get("/scim/v2/Users/" + id)))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(header().string("ETag", "W/\"1\""));
  }

  @Test
  void deleting_removes_the_keycloak_user_and_the_resource() throws Exception {
    String id = createdId("jane");
    UUID kc = keycloakUserOf(id);

    mvc.perform(as(acme, delete("/scim/v2/Users/" + id))).andExpect(status().isNoContent());
    assertThat(directory.users).doesNotContainKey(kc);
    mvc.perform(as(acme, get("/scim/v2/Users/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(acme, delete("/scim/v2/Users/" + id))).andExpect(status().isNotFound());
  }

  @Test
  void malformed_requests_get_scim_errors() throws Exception {
    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidSyntax"));
    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content("{\"schemas\":[]}"))
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content("[]"))
        .andExpect(jsonPath("$.scimType").value("invalidSyntax"));
    mvc.perform(as(acme, post("/scim/v2/Users")).contentType("text/plain").content("x"))
        .andExpect(jsonPath("$.scimType").value("invalidSyntax"));
    mvc.perform(as(acme, get("/scim/v2/Users/not-a-uuid"))).andExpect(status().isNotFound());
    mvc.perform(as(acme, post("/scim/v2/Users")).contentType(SCIM).content("{\"userName\":42}"))
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    mvc.perform(
            as(acme, post("/scim/v2/Users")).contentType(SCIM).content("{\"userName\":\"nobody\"}"))
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
  }

  @Test
  void a_new_sso_connection_links_the_tenants_existing_users() throws Exception {
    String id = createdId("jane");
    var admin =
        com.meterbook.identity.security.TestCallers.tenantUser(
            UUID.randomUUID(), acme.tenant(), com.meterbook.identity.security.Role.TENANT_ADMIN);
    String certificate = com.meterbook.identity.sso.SigningCertificateTest.pem("idp-valid.crt");
    String body =
        "{\"display_name\":\"Okta\",\"protocol\":\"saml\",\"saml\":{\"entity_id\":\"http://www.okta.com/x\",\"sso_url\":\"https://acme.okta.com/sso\",\"signing_certificate\":"
            + json.writeValueAsString(certificate)
            + "}}";

    mvc.perform(
            post("/v1/sso-connections")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(admin))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated());

    assertThat(directory.links.get(keycloakUserOf(id)))
        .hasSize(1)
        .containsValue(acme.email("jane"));
  }
}
