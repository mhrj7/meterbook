package com.meterbook.identity.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /v1/audit-events}: newest first, cursor-paged, filtered, admins only. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class AuditEventApiTest {

  @Autowired MockMvc mvc;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;

  private UUID tenant;
  private CallerAuthentication admin;

  @BeforeEach
  void tenant() {
    tenant = UUID.randomUUID();
    scope.inTenant(
        tenant,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, tenant)
                .set(Tables.TENANTS.SLUG, "t-" + tenant.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Tenant")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    admin = TestCallers.tenantUser(UUID.randomUUID(), tenant, Role.TENANT_ADMIN);
  }

  private String project(String slug) throws Exception {
    String body =
        mvc.perform(
                post("/v1/projects")
                    .with(authentication(admin))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asString();
  }

  private JsonNode list(String query) throws Exception {
    return json.readTree(
        mvc.perform(get("/v1/audit-events" + query).with(authentication(admin)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void events_are_listed_newest_first_with_actor_target_and_request_id() throws Exception {
    String first = project("one");
    String second = project("two");

    JsonNode page = list("");

    assertThat(page.get("has_more").asBoolean()).isFalse();
    JsonNode newest = page.get("data").get(0);
    assertThat(newest.get("action").asString()).isEqualTo("project.created");
    assertThat(newest.get("outcome").asString()).isEqualTo("success");
    assertThat(newest.get("target").get("id").asString()).isEqualTo(second);
    assertThat(newest.get("target").get("type").asString()).isEqualTo("project");
    assertThat(newest.get("actor").get("type").asString()).isEqualTo("user");
    assertThat(newest.get("actor").get("id").asString())
        .isEqualTo(admin.caller().userId().toString());
    assertThat(newest.get("request_id").asString()).startsWith("req_");
    assertThat(newest.has("occurred_at")).isTrue();
    assertThat(page.get("data").get(1).get("target").get("id").asString()).isEqualTo(first);
  }

  @Test
  void pages_follow_each_other_without_gaps_or_repeats() throws Exception {
    for (int i = 0; i < 5; i++) {
      project("p" + i);
    }
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page = list("?limit=2" + (cursor == null ? "" : "&starting_after=" + cursor));
      page.get("data").forEach(e -> seen.add(e.get("id").asString()));
      cursor = page.get("has_more").asBoolean() ? page.get("next_cursor").asString() : null;
      pages++;
    } while (cursor != null);

    assertThat(pages).isEqualTo(3);
    assertThat(seen).hasSize(5).doesNotHaveDuplicates();
  }

  @Test
  void events_can_be_filtered_by_action_and_target() throws Exception {
    String renamed = project("one");
    project("two");
    mvc.perform(
            patch("/v1/projects/" + renamed)
                .with(authentication(admin))
                .contentType("application/json")
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk());

    assertThat(list("?action=project.updated").get("data")).hasSize(1);
    assertThat(list("?target_id=" + renamed).get("data")).hasSize(2);
    assertThat(list("?action=project.created&target_id=" + renamed).get("data")).hasSize(1);
  }

  @Test
  void only_tenant_admins_read_the_log_and_trying_is_recorded() throws Exception {
    CallerAuthentication member = TestCallers.tenantUser(UUID.randomUUID(), tenant);
    mvc.perform(get("/v1/audit-events").with(authentication(member)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_admin_required"));
    mvc.perform(
            get("/v1/audit-events")
                .with(authentication(TestCallers.platformAdmin(UUID.randomUUID()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_required"));

    JsonNode denied = list("?action=audit_events.read").get("data");
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).get("outcome").asString()).isEqualTo("denied");
    assertThat(denied.get(0).get("actor").get("id").asString())
        .isEqualTo(member.caller().userId().toString());
    assertThat(denied.get(0).get("target").get("id").isNull()).isTrue();
  }

  @Test
  void the_limit_may_be_anything_from_1_to_200() throws Exception {
    project("one");
    assertThat(list("?limit=1").get("data")).hasSize(1);
    assertThat(list("?limit=200").get("data")).hasSize(1);
  }

  @Test
  void bad_parameters_are_rejected() throws Exception {
    for (String query :
        List.of(
            "?limit=0",
            "?limit=201",
            "?starting_after=not-a-cursor",
            "?starting_after=" + "bm8tYmFy",
            "?action=DROP%20TABLE",
            "?target_id=42")) {
      mvc.perform(get("/v1/audit-events" + query).with(authentication(admin)))
          .andExpect(status().isUnprocessableContent());
    }
  }

  @Test
  void a_cursor_does_not_cross_tenants() throws Exception {
    for (int i = 0; i < 3; i++) {
      project("p" + i);
    }
    String cursor = list("?limit=1").get("next_cursor").asString();
    UUID other = UUID.randomUUID();
    scope.inTenant(
        other,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, other)
                .set(Tables.TENANTS.SLUG, "t-" + other.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Other")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    CallerAuthentication otherAdmin =
        TestCallers.tenantUser(UUID.randomUUID(), other, Role.TENANT_ADMIN);

    mvc.perform(get("/v1/audit-events?starting_after=" + cursor).with(authentication(otherAdmin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }
}
