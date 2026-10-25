package com.meterbook.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.scim.ScimTokens;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TenantMembers;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
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

/** Role bindings (ADR-0014): to users and SCIM groups, effective at once, never above one's own. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, RoleBindingApiTest.Fixture.class})
class RoleBindingApiTest {

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
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;
  @Autowired FakeDirectory directory;
  @Autowired ScimTokens tokens;
  @Autowired RoleBindings bindings;

  private UUID tenant;
  private String domain;
  private CallerAuthentication admin;
  private String scimToken;

  @BeforeEach
  void tenant() {
    directory.reset();
    tenant = newTenant();
    domain = "t" + tenant.toString().substring(0, 8) + ".test";
    scope.inTenant(
        tenant,
        () ->
            db.insertInto(Tables.TENANT_DOMAINS)
                .set(Tables.TENANT_DOMAINS.DOMAIN, domain)
                .set(Tables.TENANT_DOMAINS.TENANT_ID, tenant)
                .execute());
    admin = TenantMembers.with(db, scope, tenant, TenantRole.ADMIN);
    scimToken = TestCallers.as(admin, () -> tokens.issue(admin.caller(), "rbac")).secret();
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

  /** A user who is a member of the tenant's organization, with no roles yet. */
  private CallerAuthentication member() {
    CallerAuthentication user = TestCallers.tenantUser(UUID.randomUUID(), tenant);
    directory.addMember(tenant, user.caller().userId());
    return user;
  }

  private ResultActions bind(CallerAuthentication caller, String body) throws Exception {
    return mvc.perform(
        post("/v1/role-bindings")
            .with(authentication(caller))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(body));
  }

  private String bindUser(UUID user, String role) throws Exception {
    String body =
        bind(admin, "{\"role\":\"" + role + "\",\"user_id\":\"" + user + "\"}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asString();
  }

  private ResultActions unbind(CallerAuthentication caller, String id) throws Exception {
    return mvc.perform(delete("/v1/role-bindings/" + id).with(authentication(caller)));
  }

  private ResultActions createProject(CallerAuthentication caller) throws Exception {
    return mvc.perform(
        post("/v1/projects")
            .with(authentication(caller))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(
                "{\"name\":\"P\",\"slug\":\"p-"
                    + UUID.randomUUID().toString().substring(0, 8)
                    + "\"}"));
  }

  private JsonNode scimUser(String local, UUID keycloakUser) throws Exception {
    directory.users.put(keycloakUser, local + "@" + domain);
    String body =
        mvc.perform(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer " + scimToken)
                    .contentType(SCIM)
                    .content(
                        """
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s@%s","active":true,
                         "emails":[{"value":"%s@%s","primary":true}]}"""
                            .formatted(local, domain, local, domain)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  private String scimGroup(String name, String member) throws Exception {
    String body =
        mvc.perform(
                post("/scim/v2/Groups")
                    .header("Authorization", "Bearer " + scimToken)
                    .contentType(SCIM)
                    .content(
                        "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\""
                            + name
                            + "\",\"members\":[{\"value\":\""
                            + member
                            + "\"}]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asString();
  }

  @Test
  void a_granted_role_works_on_the_next_request_and_a_removed_one_stops() throws Exception {
    CallerAuthentication dev = member();
    createProject(dev).andExpect(status().isForbidden());

    String binding = bindUser(dev.caller().userId(), "developer");
    createProject(dev).andExpect(status().isCreated());
    mvc.perform(get("/v1/me").with(authentication(dev)))
        .andExpect(jsonPath("$.tenant_roles[0]").value("developer"))
        .andExpect(jsonPath("$.permissions").isArray());

    unbind(admin, binding).andExpect(status().isNoContent());
    createProject(dev)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("permission_required"));
  }

  @Test
  void a_scim_group_binding_follows_membership_and_deactivation() throws Exception {
    UUID keycloakUser = UUID.randomUUID();
    CallerAuthentication jane = TestCallers.tenantUser(keycloakUser, tenant);
    directory.addMember(tenant, keycloakUser);
    // The fake directory finds the user by email, so the SCIM user is linked to this Keycloak user.
    JsonNode user = scimUser("jane", keycloakUser);
    String group = scimGroup("Engineering", user.get("id").asString());

    bind(admin, "{\"role\":\"developer\",\"group_id\":\"" + group + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.subject.type").value("group"));
    createProject(jane).andExpect(status().isCreated());

    // Removed from the group by the IdP: the role is gone at once.
    mvc.perform(
            patch("/scim/v2/Groups/" + group)
                .header("Authorization", "Bearer " + scimToken)
                .contentType(SCIM)
                .content(
                    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"remove\",\"path\":\"members[value eq \\\""
                        + user.get("id").asString()
                        + "\\\"]\"}]}"))
        .andExpect(status().isOk());
    createProject(jane).andExpect(status().isForbidden());

    // A deactivated SCIM user holds nothing, even a direct binding, before their token expires.
    bindUser(keycloakUser, "developer");
    createProject(jane).andExpect(status().isCreated());
    mvc.perform(
            patch("/scim/v2/Users/" + user.get("id").asString())
                .header("Authorization", "Bearer " + scimToken)
                .contentType(SCIM)
                .content(
                    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}"))
        .andExpect(status().isOk());
    createProject(jane).andExpect(status().isForbidden());
  }

  @Test
  void deleting_a_scim_group_removes_its_bindings() throws Exception {
    UUID keycloakUser = UUID.randomUUID();
    directory.addMember(tenant, keycloakUser);
    JsonNode user = scimUser("sam", keycloakUser);
    String group = scimGroup("Ops", user.get("id").asString());
    bind(admin, "{\"role\":\"viewer\",\"group_id\":\"" + group + "\"}")
        .andExpect(status().isCreated());

    mvc.perform(delete("/scim/v2/Groups/" + group).header("Authorization", "Bearer " + scimToken))
        .andExpect(status().isNoContent());

    mvc.perform(get("/v1/role-bindings?group_id=" + group).with(authentication(admin)))
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void subjects_must_belong_to_the_tenant() throws Exception {
    bind(admin, "{\"role\":\"viewer\",\"user_id\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("user_not_in_tenant"));
    bind(admin, "{\"role\":\"viewer\",\"group_id\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("group_not_found"));
  }

  @Test
  void another_tenants_group_looks_like_no_group_at_all() throws Exception {
    UUID other = newTenant();
    UUID otherGroup = UUID.randomUUID();
    scope.inTenant(
        other,
        () ->
            db.insertInto(Tables.SCIM_GROUPS)
                .set(Tables.SCIM_GROUPS.ID, otherGroup)
                .set(Tables.SCIM_GROUPS.TENANT_ID, other)
                .set(Tables.SCIM_GROUPS.DISPLAY_NAME, "Theirs")
                .set(Tables.SCIM_GROUPS.DISPLAY_NAME_KEY, "theirs")
                .set(Tables.SCIM_GROUPS.VERSION, 1L)
                .set(Tables.SCIM_GROUPS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(Tables.SCIM_GROUPS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());

    bind(admin, "{\"role\":\"admin\",\"group_id\":\"" + otherGroup + "\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("group_not_found"));
  }

  @Test
  void a_request_names_exactly_one_known_role_and_subject() throws Exception {
    UUID user = member().caller().userId();
    bind(admin, "{\"role\":\"viewer\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("one_subject_required"));
    bind(admin, "{\"role\":\"viewer\",\"user_id\":\"" + user + "\",\"group_id\":\"" + user + "\"}")
        .andExpect(jsonPath("$.error.code").value("one_subject_required"));
    bind(admin, "{\"role\":\"owner\",\"user_id\":\"" + user + "\"}")
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  void a_subject_holds_a_role_once() throws Exception {
    UUID user = member().caller().userId();
    bindUser(user, "viewer");
    bind(admin, "{\"role\":\"viewer\",\"user_id\":\"" + user + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("role_binding_exists"));
    bindUser(user, "auditor");

    UUID keycloakUser = UUID.randomUUID();
    directory.addMember(tenant, keycloakUser);
    String group = scimGroup("Finance", scimUser("fin", keycloakUser).get("id").asString());
    bind(admin, "{\"role\":\"viewer\",\"group_id\":\"" + group + "\"}")
        .andExpect(status().isCreated());
    bind(admin, "{\"role\":\"viewer\",\"group_id\":\"" + group + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("role_binding_exists"));
  }

  @Test
  void a_binding_is_recorded_with_its_role_and_subject() throws Exception {
    UUID user = member().caller().userId();
    String id = bindUser(user, "auditor");
    unbind(admin, id).andExpect(status().isNoContent());

    var details =
        scope.inTenant(
            tenant,
            () ->
                db.select(Tables.AUDIT_EVENTS.ACTION, Tables.AUDIT_EVENTS.DETAILS)
                    .from(Tables.AUDIT_EVENTS)
                    .where(Tables.AUDIT_EVENTS.TARGET_ID.eq(UUID.fromString(id)))
                    .fetchMap(Tables.AUDIT_EVENTS.ACTION, r -> r.value2().data()));
    assertThat(details).containsOnlyKeys("role_binding.created", "role_binding.deleted");
    assertThat(details.values())
        .allSatisfy(
            d ->
                assertThat(d)
                    .contains(
                        "\"role\": \"auditor\"", "\"subject_type\": \"user\"", user.toString()));
  }

  @Test
  void only_admins_bind_and_nobody_grants_above_their_own_role() throws Exception {
    CallerAuthentication dev = TenantMembers.with(db, scope, tenant, TenantRole.DEVELOPER);
    bind(dev, "{\"role\":\"viewer\",\"user_id\":\"" + member().caller().userId() + "\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("permission_required"));

    // Below the API: a caller holding fewer permissions than the role is refused by the service.
    UUID target = member().caller().userId();
    assertThatThrownBy(
            () ->
                TestCallers.as(
                    dev,
                    () ->
                        bindings.create(
                            dev.caller(), TenantRole.ADMIN, RoleBindings.Subject.user(target))))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("role_above_own"));
    String binding = bindUser(target, "admin");
    assertThatThrownBy(
            () ->
                TestCallers.as(
                    dev,
                    () -> {
                      bindings.delete(dev.caller(), UUID.fromString(binding));
                      return null;
                    }))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("role_above_own"));
  }

  @Test
  void the_last_admin_bound_to_a_user_cannot_be_removed() throws Exception {
    String own =
        json.readTree(
                mvc.perform(
                        get("/v1/role-bindings?user_id=" + admin.caller().userId())
                            .with(authentication(admin)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data")
            .get(0)
            .get("id")
            .asString();
    unbind(admin, own)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("last_admin"));

    // An admin group does not count: its members change with the IdP.
    UUID keycloakUser = UUID.randomUUID();
    directory.addMember(tenant, keycloakUser);
    String group = scimGroup("Admins", scimUser("ada", keycloakUser).get("id").asString());
    bind(admin, "{\"role\":\"admin\",\"group_id\":\"" + group + "\"}")
        .andExpect(status().isCreated());
    unbind(admin, own).andExpect(status().isConflict());

    CallerAuthentication second = member();
    bindUser(second.caller().userId(), "admin");
    unbind(admin, own).andExpect(status().isNoContent());
    // The former admin holds nothing now; the second admin carries on.
    createProject(admin).andExpect(status().isForbidden());
    createProject(second).andExpect(status().isCreated());
  }

  @Test
  void bindings_are_listed_filtered_and_paged() throws Exception {
    UUID user = member().caller().userId();
    bindUser(user, "viewer");
    bindUser(user, "auditor");

    mvc.perform(get("/v1/role-bindings?user_id=" + user).with(authentication(admin)))
        .andExpect(jsonPath("$.data.length()").value(2));
    mvc.perform(get("/v1/role-bindings?limit=200").with(authentication(admin)))
        .andExpect(status().isOk());
    mvc.perform(get("/v1/role-bindings?limit=1").with(authentication(admin)))
        .andExpect(status().isOk());
    String page =
        mvc.perform(get("/v1/role-bindings?limit=1").with(authentication(admin)))
            .andExpect(jsonPath("$.has_more").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            get("/v1/role-bindings?limit=5&starting_after="
                    + json.readTree(page).get("next_cursor").asString())
                .with(authentication(admin)))
        .andExpect(jsonPath("$.data.length()").value(2));
    for (String query :
        new String[] {
          "?limit=0",
          "?user_id=x",
          "?group_id=x",
          "?starting_after=x",
          "?user_id=" + user + "&group_id=" + user
        }) {
      mvc.perform(get("/v1/role-bindings" + query).with(authentication(admin)))
          .andExpect(status().isUnprocessableContent());
    }
  }

  @Test
  void roles_are_listed_with_their_permissions() throws Exception {
    CallerAuthentication viewer = TenantMembers.with(db, scope, tenant, TenantRole.VIEWER);
    mvc.perform(get("/v1/roles").with(authentication(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4))
        .andExpect(jsonPath("$.data[0].name").value("viewer"))
        .andExpect(jsonPath("$.data[0].permissions[0]").value("tenants:read"))
        .andExpect(jsonPath("$.data[3].name").value("admin"));
    mvc.perform(get("/v1/me").with(authentication(viewer)))
        .andExpect(jsonPath("$.tenant_roles[0]").value("viewer"))
        .andExpect(jsonPath("$.permissions[0]").value("api_keys:read"));
  }

  @Test
  void a_directory_outage_refuses_a_user_binding_without_changing_anything() throws Exception {
    UUID user = member().caller().userId();
    directory.failOn("organizationsOf", new Directory.UnavailableException("down", null));

    bind(admin, "{\"role\":\"viewer\",\"user_id\":\"" + user + "\"}")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("directory_unavailable"));
    assertThat(bindings.rolesOf(tenant, user)).isEmpty();
  }

  @Test
  void a_tenant_has_at_most_500_bindings() throws Exception {
    for (int i = 1; i < RoleBindings.MAX_BINDINGS; i++) {
      TenantMembers.grant(db, scope, tenant, UUID.randomUUID(), TenantRole.VIEWER);
    }
    bind(admin, "{\"role\":\"viewer\",\"user_id\":\"" + member().caller().userId() + "\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("role_binding_limit_reached"));
  }
}
