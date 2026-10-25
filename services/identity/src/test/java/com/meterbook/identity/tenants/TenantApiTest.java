package com.meterbook.identity.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TenantApiTest.Fixture.class})
class TenantApiTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired FakeDirectory directory;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;

  private final CallerAuthentication operator = TestCallers.platformAdmin(UUID.randomUUID());
  private String suffix;

  @BeforeEach
  void reset() {
    directory.reset();
    suffix = UUID.randomUUID().toString().substring(0, 8);
  }

  private String body(String slug, String domain, String adminEmail) {
    return """
        {"name":"Acme Ltd","slug":"%s","domains":["%s"],"initial_admin_email":"%s"}"""
        .formatted(slug, domain, adminEmail);
  }

  private ResultActions create(CallerAuthentication caller, String key, String body)
      throws Exception {
    return mvc.perform(
        post("/v1/tenants")
            .with(authentication(caller))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  private boolean platform(org.jooq.Condition condition) {
    return scope.asPlatform("test assertion", () -> db.fetchExists(Tables.TENANTS, condition));
  }

  private ResultActions createAcme() throws Exception {
    return create(
        operator,
        UUID.randomUUID().toString(),
        body("acme-" + suffix, "acme-" + suffix + ".test", "ada@acme-" + suffix + ".test"));
  }

  @Test
  void a_platform_admin_creates_a_tenant_with_its_first_admin() throws Exception {
    String response =
        create(
                operator,
                "k1",
                body("acme-" + suffix, "ACME-" + suffix + ".Test", "Ada@ACME-" + suffix + ".test"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("acme-" + suffix))
            .andExpect(jsonPath("$.name").value("Acme Ltd"))
            .andExpect(jsonPath("$.domains[0]").value("acme-" + suffix + ".test"))
            .andExpect(jsonPath("$.created_at").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json.readTree(response).get("id").asString());

    assertThat(directory.organizations).containsKey(id);
    UUID admin = directory.findUserByEmail("ada@acme-" + suffix + ".test").orElseThrow();
    assertThat(directory.members.get(admin)).containsExactly(id);
    // The first admin is a role binding (ADR-0014); Keycloak roles are left alone.
    assertThat(admins(id)).containsExactly(admin);
    assertThat(directory.roles).doesNotContainKey(admin);
    assertThat(platform(Tables.TENANTS.ID.eq(id))).isTrue();
  }

  private java.util.List<UUID> admins(UUID tenant) {
    return scope.inTenant(
        tenant,
        () ->
            db.select(Tables.ROLE_BINDINGS.USER_ID)
                .from(Tables.ROLE_BINDINGS)
                .where(Tables.ROLE_BINDINGS.ROLE.eq("admin"))
                .fetch(Tables.ROLE_BINDINGS.USER_ID));
  }

  @Test
  void the_location_header_points_at_the_new_tenant() throws Exception {
    String location = createAcme().andReturn().getResponse().getHeader("Location");
    assertThat(location).matches("/v1/tenants/[0-9a-f-]{36}");
  }

  @Test
  void an_existing_directory_user_without_a_tenant_becomes_the_admin() throws Exception {
    UUID existing = directory.createUser("ada@acme-" + suffix + ".test", "", "");

    String response =
        createAcme().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

    assertThat(directory.calls("createUser")).isEqualTo(1);
    assertThat(admins(UUID.fromString(json.readTree(response).get("id").asString())))
        .containsExactly(existing);
  }

  @Test
  void retrying_with_the_same_key_creates_nothing_new() throws Exception {
    String body =
        body("acme-" + suffix, "acme-" + suffix + ".test", "ada@acme-" + suffix + ".test");
    String first = create(operator, "same", body).andReturn().getResponse().getContentAsString();

    create(operator, "same", body)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json(first));
    assertThat(directory.calls("createOrganization")).isEqualTo(1);
  }

  @Test
  void only_platform_admins_create_tenants() throws Exception {
    create(
            TestCallers.tenantUser(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN),
            "k",
            body("acme-" + suffix, "acme.test", "a@acme.test"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("platform_admin_required"));
    assertThat(directory.calls("createOrganization")).isZero();
  }

  @Test
  void unauthenticated_requests_are_401() throws Exception {
    mvc.perform(
            post("/v1/tenants")
                .header("Idempotency-Key", "k")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validates_every_field() throws Exception {
    create(operator, "v1", body("Bad_Slug", "acme.test", "a@acme.test"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.param").value("slug"));
    create(operator, "v2", body("acme-" + suffix, "not a domain", "a@acme.test"))
        .andExpect(jsonPath("$.error.param").value("domains[0]"));
    create(operator, "v3", body("acme-" + suffix, "acme.test", "not-an-email"))
        .andExpect(jsonPath("$.error.param").value("initial_admin_email"));
    create(
            operator,
            "v4",
            "{\"name\":\"A\",\"slug\":\"acme\",\"domains\":[],\"initial_admin_email\":\"a@acme.test\"}")
        .andExpect(jsonPath("$.error.param").value("domains"));
    create(operator, "v5", body("acme-" + suffix, "acme.test", "a@elsewhere.test"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("admin_email_outside_domains"));
    assertThat(directory.calls("createOrganization")).isZero();
  }

  @Test
  void a_slug_or_domain_can_belong_to_one_tenant_only() throws Exception {
    createAcme().andExpect(status().isCreated());

    create(
            operator,
            "dup-slug",
            body("acme-" + suffix, "other-" + suffix + ".test", "a@other-" + suffix + ".test"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("slug_taken"));
    create(
            operator,
            "dup-domain",
            body("other-" + suffix, "acme-" + suffix + ".test", "a@acme-" + suffix + ".test"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("domain_already_claimed"));
  }

  @Test
  void an_admin_who_already_belongs_to_a_tenant_is_refused_and_nothing_is_left_behind()
      throws Exception {
    UUID user = directory.createUser("ada@acme-" + suffix + ".test", "", "");
    directory.addMember(UUID.randomUUID(), user);

    createAcme()
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("admin_belongs_to_another_tenant"));

    assertThat(directory.organizations).isEmpty();
    assertThat(directory.users).containsKey(user);
  }

  @Test
  void a_directory_outage_midway_undoes_everything_and_asks_to_retry() throws Exception {
    directory.failOn("addMember", new Directory.UnavailableException("down", null));
    String body =
        body("acme-" + suffix, "acme-" + suffix + ".test", "ada@acme-" + suffix + ".test");

    create(operator, "outage", body)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(jsonPath("$.error.code").value("directory_unavailable"));

    assertThat(directory.organizations).isEmpty();
    assertThat(directory.users).isEmpty();
    assertThat(platform(Tables.TENANTS.SLUG.eq("acme-" + suffix))).isFalse();

    directory.reset();
    create(operator, "outage", body).andExpect(status().isCreated());
  }

  @Test
  void a_directory_conflict_is_reported_as_a_conflict() throws Exception {
    directory.failOn("createOrganization", new Directory.ConflictException("taken"));

    createAcme()
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("tenant_conflict"));
  }

  @Test
  void a_database_conflict_after_keycloak_changed_undoes_the_keycloak_changes() throws Exception {
    String slug = "acme-" + suffix;
    // Another request claims the slug between our check and our insert.
    directory.onCall(
        "addMember",
        () ->
            scope.asPlatform(
                "a concurrent tenant signup",
                () ->
                    db.insertInto(Tables.TENANTS)
                        .set(Tables.TENANTS.ID, UUID.randomUUID())
                        .set(Tables.TENANTS.SLUG, slug)
                        .set(Tables.TENANTS.NAME, "racer")
                        .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .execute()));

    create(operator, "race", body(slug, slug + ".test", "ada@" + slug + ".test"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("tenant_conflict"));
    assertThat(directory.organizations).isEmpty();
    assertThat(directory.users).isEmpty();
  }

  @Test
  void a_failed_undo_does_not_hide_the_original_error() throws Exception {
    directory.failOn("addMember", new Directory.UnavailableException("down", null));
    directory.failOn("deleteOrganization", new Directory.UnavailableException("still down", null));

    createAcme().andExpect(status().isServiceUnavailable());
  }

  @Test
  void tenants_are_visible_to_operators_and_to_their_own_users_only() throws Exception {
    String location = createAcme().andReturn().getResponse().getHeader("Location");
    UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

    mvc.perform(get(location).with(authentication(operator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
    mvc.perform(
            get(location)
                .with(
                    authentication(
                        com.meterbook.identity.support.TenantMembers.with(
                            db, scope, id, com.meterbook.identity.authz.TenantRole.VIEWER))))
        .andExpect(status().isOk());
    String other =
        create(
                operator,
                "k-other",
                body(
                    "other-" + suffix,
                    "other-" + suffix + ".test",
                    "eve@other-" + suffix + ".test"))
            .andReturn()
            .getResponse()
            .getHeader("Location");
    UUID otherId = UUID.fromString(other.substring(other.lastIndexOf('/') + 1));
    // A user of another tenant may read tenants, but not this one: it does not exist for them.
    mvc.perform(
            get(location)
                .with(
                    authentication(
                        com.meterbook.identity.support.TenantMembers.with(
                            db, scope, otherId, com.meterbook.identity.authz.TenantRole.VIEWER))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("tenant_not_found"));
    // Without a role binding, nothing is readable at all: refused before looking.
    mvc.perform(
            get(location)
                .with(authentication(TestCallers.tenantUser(UUID.randomUUID(), UUID.randomUUID()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("permission_required"));
    mvc.perform(get("/v1/tenants/" + UUID.randomUUID()).with(authentication(operator)))
        .andExpect(status().isNotFound());
  }
}
