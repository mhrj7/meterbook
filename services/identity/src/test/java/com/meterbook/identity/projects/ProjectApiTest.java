package com.meterbook.identity.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ProjectApiTest {

  @Autowired MockMvc mvc;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;

  private UUID tenant;
  private CallerAuthentication admin;
  private CallerAuthentication member;

  @BeforeEach
  void tenant() {
    tenant = newTenant();
    admin = TestCallers.tenantUser(UUID.randomUUID(), tenant, Role.TENANT_ADMIN);
    member = TestCallers.tenantUser(UUID.randomUUID(), tenant);
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

  private ResultActions create(CallerAuthentication caller, String key, String slug)
      throws Exception {
    return mvc.perform(
        post("/v1/projects")
            .with(authentication(caller))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content("{\"name\":\"Checkout\",\"slug\":\"" + slug + "\"}"));
  }

  private String created(String slug) throws Exception {
    String body =
        create(admin, UUID.randomUUID().toString(), slug)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asString();
  }

  private ResultActions addEnvironment(
      CallerAuthentication caller, String project, String slug, String kind) throws Exception {
    return mvc.perform(
        post("/v1/projects/" + project + "/environments")
            .with(authentication(caller))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content("{\"name\":\"Staging\",\"slug\":\"" + slug + "\",\"kind\":\"" + kind + "\"}"));
  }

  @Test
  void a_new_project_gets_a_live_production_and_a_test_sandbox() throws Exception {
    create(admin, "first", "checkout")
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/v1/projects/")))
        .andExpect(jsonPath("$.slug").value("checkout"))
        .andExpect(jsonPath("$.environments.length()").value(2))
        .andExpect(jsonPath("$.environments[0].slug").value("production"))
        .andExpect(jsonPath("$.environments[0].kind").value("live"))
        .andExpect(jsonPath("$.environments[1].slug").value("sandbox"))
        .andExpect(jsonPath("$.environments[1].kind").value("test"))
        .andExpect(jsonPath("$.created_at").exists());
  }

  @Test
  void retries_are_idempotent_and_slugs_are_unique_per_tenant() throws Exception {
    String first = create(admin, "same", "billing").andReturn().getResponse().getContentAsString();
    String again =
        create(admin, "same", "billing")
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(again).isEqualTo(first);

    create(admin, "other", "billing")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("project_slug_taken"));

    // Another tenant may use the same slug.
    var globex = TestCallers.tenantUser(UUID.randomUUID(), newTenant(), Role.TENANT_ADMIN);
    create(globex, "globex", "billing").andExpect(status().isCreated());
  }

  @Test
  void members_read_and_only_admins_write() throws Exception {
    String id = created("reports");

    mvc.perform(get("/v1/projects/" + id).with(authentication(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(get("/v1/projects").with(authentication(member)))
        .andExpect(jsonPath("$.data[0].id").value(id));
    create(member, "member", "nope")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_admin_required"));
    addEnvironment(member, id, "qa", "test").andExpect(status().isForbidden());
    mvc.perform(
            patch("/v1/projects/" + id)
                .with(authentication(member))
                .contentType("application/json")
                .content("{\"name\":\"x\"}"))
        .andExpect(status().isForbidden());

    var operator = TestCallers.platformAdmin(UUID.randomUUID());
    mvc.perform(get("/v1/projects").with(authentication(operator)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_required"));
  }

  @Test
  void renames_a_project() throws Exception {
    String id = created("rename-me");

    mvc.perform(
            patch("/v1/projects/" + id)
                .with(authentication(admin))
                .contentType("application/json")
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"))
        .andExpect(jsonPath("$.slug").value("rename-me"))
        .andExpect(jsonPath("$.environments.length()").value(2));
    mvc.perform(
            patch("/v1/projects/" + UUID.randomUUID())
                .with(authentication(admin))
                .contentType("application/json")
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("project_not_found"));
  }

  @Test
  void validates_requests() throws Exception {
    create(admin, "upper", "Checkout").andExpect(status().isUnprocessableContent());
    create(admin, "dash", "-checkout").andExpect(status().isUnprocessableContent());
    String id = created("valid");
    addEnvironment(admin, id, "qa", "prod").andExpect(status().isUnprocessableContent());
    mvc.perform(get("/v1/projects?limit=0").with(authentication(admin)))
        .andExpect(status().isUnprocessableContent());
    mvc.perform(get("/v1/projects?starting_after=nope").with(authentication(admin)))
        .andExpect(jsonPath("$.error.code").value("cursor_invalid"));
  }

  @Test
  void adds_lists_and_gets_environments() throws Exception {
    String id = created("envs");
    String body =
        addEnvironment(admin, id, "staging", "test")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.project_id").value(id))
            .andExpect(jsonPath("$.kind").value("test"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String env = json.readTree(body).get("id").asString();

    addEnvironment(admin, id, "staging", "live")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("environment_slug_taken"));
    mvc.perform(get("/v1/projects/" + id + "/environments?limit=2").with(authentication(member)))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.has_more").value(true));
    mvc.perform(get("/v1/projects/" + id + "/environments/" + env).with(authentication(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("staging"));
    mvc.perform(
            get("/v1/projects/" + id + "/environments/" + UUID.randomUUID())
                .with(authentication(member)))
        .andExpect(jsonPath("$.error.code").value("environment_not_found"));
    mvc.perform(
            get("/v1/projects/" + UUID.randomUUID() + "/environments").with(authentication(member)))
        .andExpect(jsonPath("$.error.code").value("project_not_found"));
    addEnvironment(admin, UUID.randomUUID().toString(), "qa", "test")
        .andExpect(jsonPath("$.error.code").value("project_not_found"));
  }

  @Test
  void pages_through_projects() throws Exception {
    created("page-a");
    created("page-b");
    created("page-c");

    String first =
        mvc.perform(get("/v1/projects?limit=2").with(authentication(member)))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.has_more").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = json.readTree(first).get("next_cursor").asString();
    mvc.perform(get("/v1/projects?limit=2&starting_after=" + cursor).with(authentication(member)))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.has_more").value(false))
        .andExpect(jsonPath("$.data[0].environments.length()").value(2));
  }

  @Test
  void a_tenant_has_at_most_100_projects_and_a_project_20_environments() throws Exception {
    String id = created("limits");
    scope.inTenant(
        tenant,
        () -> {
          for (int i = 1; i < Projects.MAX_PROJECTS; i++) {
            db.insertInto(Tables.PROJECTS)
                .set(Tables.PROJECTS.ID, UUID.randomUUID())
                .set(Tables.PROJECTS.TENANT_ID, tenant)
                .set(Tables.PROJECTS.SLUG, "bulk-" + i)
                .set(Tables.PROJECTS.NAME, "Bulk")
                .set(Tables.PROJECTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
          }
          for (int i = 2; i < Projects.MAX_ENVIRONMENTS; i++) {
            db.insertInto(Tables.ENVIRONMENTS)
                .set(Tables.ENVIRONMENTS.ID, UUID.randomUUID())
                .set(Tables.ENVIRONMENTS.TENANT_ID, tenant)
                .set(Tables.ENVIRONMENTS.PROJECT_ID, UUID.fromString(id))
                .set(Tables.ENVIRONMENTS.SLUG, "bulk-" + i)
                .set(Tables.ENVIRONMENTS.NAME, "Bulk")
                .set(Tables.ENVIRONMENTS.KIND, "test")
                .set(Tables.ENVIRONMENTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
          }
        });

    create(admin, "one-too-many", "overflow")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("project_limit_reached"));
    addEnvironment(admin, id, "overflow", "test")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("environment_limit_reached"));
  }

  @Test
  void another_tenants_projects_and_environments_do_not_exist() throws Exception {
    String id = created("secret");
    String env =
        json.readTree(
                mvc.perform(get("/v1/projects/" + id).with(authentication(admin)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("environments")
            .get(0)
            .get("id")
            .asString();
    var intruder = TestCallers.tenantUser(UUID.randomUUID(), newTenant(), Role.TENANT_ADMIN);

    mvc.perform(get("/v1/projects").with(authentication(intruder)))
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(get("/v1/projects/" + id).with(authentication(intruder)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v1/projects/" + id + "/environments").with(authentication(intruder)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v1/projects/" + id + "/environments/" + env).with(authentication(intruder)))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/v1/projects/" + id)
                .with(authentication(intruder))
                .contentType("application/json")
                .content("{\"name\":\"pwned\"}"))
        .andExpect(status().isNotFound());
    addEnvironment(intruder, id, "backdoor", "live").andExpect(status().isNotFound());

    mvc.perform(get("/v1/projects/" + id).with(authentication(admin)))
        .andExpect(jsonPath("$.name").value("Checkout"))
        .andExpect(jsonPath("$.environments.length()").value(2));
  }
}
