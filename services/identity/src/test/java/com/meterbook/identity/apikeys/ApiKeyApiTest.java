package com.meterbook.identity.apikeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.authz.TenantRole;
import com.meterbook.identity.db.Tables;
import com.meterbook.identity.projects.Projects;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.MutableClock;
import com.meterbook.identity.support.TenantMembers;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
@Import({TestDatabase.class, ApiKeyApiTest.Fixture.class})
class ApiKeyApiTest {

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
  @Autowired Projects projects;
  @Autowired ApiKeys keys;

  private UUID tenant;
  private CallerAuthentication admin;
  private CallerAuthentication member;
  private Projects.Project project;
  private String sandbox;
  private String production;

  @BeforeEach
  void environment() {
    tenant = newTenant();
    admin = TenantMembers.with(db, scope, tenant, TenantRole.ADMIN);
    member = TenantMembers.with(db, scope, tenant, TenantRole.VIEWER);
    project = TestCallers.as(admin, () -> projects.create(admin.caller(), "Checkout", "checkout"));
    production = project.environments().get(0).id().toString();
    sandbox = project.environments().get(1).id().toString();
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

  private String keys(String environment) {
    return "/v1/projects/" + project.id() + "/environments/" + environment + "/api-keys";
  }

  private ResultActions create(CallerAuthentication caller, String environment, String body)
      throws Exception {
    return mvc.perform(
        post(keys(environment))
            .with(authentication(caller))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(body));
  }

  private JsonNode created(String environment, String body) throws Exception {
    return json.readTree(
        create(admin, environment, body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode created() throws Exception {
    return created(sandbox, "{\"name\":\"Ingest\",\"scopes\":[\"usage:write\"]}");
  }

  private ResultActions use(String secret) throws Exception {
    return mvc.perform(get("/v1/api-key").header("Authorization", "Bearer " + secret));
  }

  private ResultActions rotate(String environment, String id, String body) throws Exception {
    return mvc.perform(
        post(keys(environment) + "/" + id + "/rotate")
            .with(authentication(admin))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(body));
  }

  private String at(Duration fromNow) {
    return OffsetDateTime.now(clock)
        .plus(fromNow)
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  @Test
  void a_key_is_shown_once_and_stored_only_as_a_hash() throws Exception {
    JsonNode key =
        created(
            production,
            "{\"name\":\"Billing sync\",\"scopes\":[\"invoices:read\",\"customers:read\"]}");
    String secret = key.get("secret").asString();

    assertThat(secret).matches("mb_live_[0-9A-Za-z]{46}");
    assertThat(ApiKeys.wellFormed(secret)).isTrue();
    assertThat(key.get("prefix").asString()).isEqualTo(secret.substring(0, 12));
    assertThat(key.get("kind").asString()).isEqualTo("live");
    // api-standards.md: timestamps in UTC, whatever zone the JVM runs in.
    assertThat(key.get("created_at").asString()).endsWith("Z");
    assertThat(key.get("scopes").toString()).isEqualTo("[\"customers:read\",\"invoices:read\"]");
    var row =
        scope.inTenant(
            tenant,
            () ->
                db.selectFrom(Tables.API_KEYS)
                    .where(Tables.API_KEYS.ID.eq(UUID.fromString(key.get("id").asString())))
                    .fetchOne());
    assertThat(row.getKeyHash()).isEqualTo(ApiKeys.hash(secret)).doesNotContain(secret);
    assertThat(
            db.fetch(
                "select response_body from idempotency_keys where response_body like ?",
                "%" + secret + "%"))
        .isEmpty();

    assertThat(created().get("secret").asString()).startsWith("mb_test_");
  }

  @Test
  void a_repeated_create_does_not_reveal_the_secret_again() throws Exception {
    String body = "{\"name\":\"Ingest\",\"scopes\":[\"usage:write\"]}";
    var first =
        mvc.perform(
            post(keys(sandbox))
                .with(authentication(admin))
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(body));
    first.andExpect(status().isCreated());

    mvc.perform(
            post(keys(sandbox))
                .with(authentication(admin))
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_used"))
        .andExpect(jsonPath("$.secret").doesNotExist());
  }

  @Test
  void a_key_says_where_it_belongs_and_may_be_cached_for_30_seconds() throws Exception {
    JsonNode key = created();

    use(key.get("secret").asString())
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=30, private"))
        .andExpect(jsonPath("$.id").value(key.get("id").asString()))
        .andExpect(jsonPath("$.tenant_id").value(tenant.toString()))
        .andExpect(jsonPath("$.project_id").value(project.id().toString()))
        .andExpect(jsonPath("$.environment_id").value(sandbox))
        .andExpect(jsonPath("$.kind").value("test"))
        .andExpect(jsonPath("$.scopes[0]").value("usage:write"));
  }

  @Test
  void anything_but_a_live_key_is_401_and_never_cacheable() throws Exception {
    String secret = created().get("secret").asString();
    String typo =
        secret.substring(0, 20) + (secret.charAt(20) == 'a' ? 'b' : 'a') + secret.substring(21);
    String unknown = "mb_test_" + "A".repeat(40);
    unknown = unknown + ApiKeys.checksum(unknown);

    for (String bad : List.of(typo, unknown, "mb_test_short", "mbscim_" + secret, secret + "x")) {
      use(bad)
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.error.code").exists());
    }
    mvc.perform(get("/v1/api-key")).andExpect(status().isUnauthorized());
    // A signed-in person is not a key.
    mvc.perform(get("/v1/api-key").with(authentication(admin)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_revoked_key_is_rejected_on_the_very_next_request() throws Exception {
    JsonNode key = created();
    String secret = key.get("secret").asString();
    String id = key.get("id").asString();
    use(secret).andExpect(status().isOk());

    mvc.perform(delete(keys(sandbox) + "/" + id).with(authentication(admin)))
        .andExpect(status().isNoContent());
    long revokedAt = System.nanoTime();

    use(secret).andExpect(status().isUnauthorized());
    assertThat(Duration.ofNanos(System.nanoTime() - revokedAt)).isLessThan(Duration.ofSeconds(60));
    mvc.perform(get(keys(sandbox)).with(authentication(member)))
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(get(keys(sandbox) + "/" + id).with(authentication(member)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("api_key_not_found"));
    mvc.perform(delete(keys(sandbox) + "/" + id).with(authentication(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_key_cannot_call_anything_but_api_key() throws Exception {
    String secret = created().get("secret").asString();

    for (String path : List.of("/v1/me", "/v1/projects", keys(sandbox), "/v1/scim-tokens")) {
      mvc.perform(get(path).header("Authorization", "Bearer " + secret))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(get("/scim/v2/Users").header("Authorization", "Bearer " + secret))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_key_stops_working_when_it_expires() throws Exception {
    String secret =
        created(
                sandbox,
                "{\"name\":\"Temp\",\"scopes\":[\"usage:read\"],\"expires_at\":\""
                    + at(Duration.ofHours(1))
                    + "\"}")
            .get("secret")
            .asString();

    use(secret).andExpect(status().isOk()).andExpect(jsonPath("$.expires_at").exists());
    clock.advance(Duration.ofHours(2));
    use(secret).andExpect(status().isUnauthorized());
  }

  @Test
  void rotation_keeps_the_old_key_working_for_the_grace_period() throws Exception {
    JsonNode old = created();
    String oldId = old.get("id").asString();
    String oldSecret = old.get("secret").asString();

    JsonNode successor =
        json.readTree(
            rotate(sandbox, oldId, "{\"grace_period_seconds\":3600}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ingest"))
                .andExpect(jsonPath("$.scopes[0]").value("usage:write"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    String newSecret = successor.get("secret").asString();
    assertThat(newSecret).isNotEqualTo(oldSecret);

    use(oldSecret).andExpect(status().isOk());
    use(newSecret).andExpect(status().isOk());
    mvc.perform(get(keys(sandbox) + "/" + oldId).with(authentication(member)))
        .andExpect(jsonPath("$.rotated_to").value(successor.get("id").asString()))
        .andExpect(jsonPath("$.expires_at").exists());
    rotate(sandbox, oldId, "{}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("api_key_already_rotated"));

    clock.advance(Duration.ofMinutes(61));
    use(oldSecret).andExpect(status().isUnauthorized());
    use(newSecret).andExpect(status().isOk());

    // No grace period: the old key retires at once.
    String next = successor.get("id").asString();
    String third =
        json.readTree(
                rotate(sandbox, next, "{\"grace_period_seconds\":0}")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("secret")
            .asString();
    use(newSecret).andExpect(status().isUnauthorized());
    use(third).andExpect(status().isOk());
  }

  @Test
  void rotation_keeps_an_earlier_expiry_and_defaults_to_a_day() throws Exception {
    JsonNode soon =
        created(
            sandbox,
            "{\"name\":\"Soon\",\"scopes\":[\"usage:read\"],\"expires_at\":\""
                + at(Duration.ofMinutes(10))
                + "\"}");
    rotate(sandbox, soon.get("id").asString(), "{}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expires_at").exists());
    mvc.perform(get(keys(sandbox) + "/" + soon.get("id").asString()).with(authentication(admin)))
        .andExpect(jsonPath("$.expires_at").value(at(Duration.ofMinutes(10))));

    JsonNode later = created();
    rotate(sandbox, later.get("id").asString(), "{}").andExpect(status().isCreated());
    clock.advance(Duration.ofHours(23));
    use(later.get("secret").asString()).andExpect(status().isOk());
    clock.advance(Duration.ofHours(2));
    use(later.get("secret").asString()).andExpect(status().isUnauthorized());
  }

  @Test
  void validates_requests() throws Exception {
    create(admin, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:delete\"]}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("scope_unknown"));
    create(admin, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:read\",\"usage:read\"]}")
        .andExpect(jsonPath("$.error.code").value("scopes_duplicated"));
    create(admin, sandbox, "{\"name\":\"x\",\"scopes\":[]}")
        .andExpect(status().isUnprocessableContent());
    create(admin, sandbox, "{\"scopes\":[\"usage:read\"]}")
        .andExpect(status().isUnprocessableContent());
    create(
            admin,
            sandbox,
            "{\"name\":\"x\",\"scopes\":[\"usage:read\"],\"expires_at\":\""
                + at(Duration.ofMinutes(-1))
                + "\"}")
        .andExpect(jsonPath("$.error.code").value("expires_at_out_of_range"));
    create(
            admin,
            sandbox,
            "{\"name\":\"x\",\"scopes\":[\"usage:read\"],\"expires_at\":\""
                + at(Duration.ofDays(731))
                + "\"}")
        .andExpect(jsonPath("$.error.code").value("expires_at_out_of_range"));
    rotate(sandbox, created().get("id").asString(), "{\"grace_period_seconds\":604801}")
        .andExpect(status().isUnprocessableContent());
    mvc.perform(get(keys(sandbox) + "?limit=0").with(authentication(admin)))
        .andExpect(jsonPath("$.error.code").value("limit_out_of_range"));
    mvc.perform(get(keys(sandbox) + "?starting_after=x").with(authentication(admin)))
        .andExpect(jsonPath("$.error.code").value("cursor_invalid"));
  }

  @Test
  void members_read_and_only_admins_manage_keys() throws Exception {
    String id = created().get("id").asString();

    mvc.perform(get(keys(sandbox)).with(authentication(member)))
        .andExpect(jsonPath("$.data[0].id").value(id))
        .andExpect(jsonPath("$.data[0].secret").doesNotExist());
    create(member, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:read\"]}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("permission_required"));
    mvc.perform(delete(keys(sandbox) + "/" + id).with(authentication(member)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(keys(sandbox) + "/" + id + "/rotate")
                .with(authentication(member))
                .header("Idempotency-Key", "member")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(keys(sandbox)).with(authentication(TestCallers.platformAdmin(UUID.randomUUID()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_required"));
  }

  @Test
  void keys_of_another_environment_or_tenant_do_not_exist() throws Exception {
    String id = created().get("id").asString();

    // Same project, other environment.
    mvc.perform(get(keys(production) + "/" + id).with(authentication(admin)))
        .andExpect(status().isNotFound());
    mvc.perform(delete(keys(production) + "/" + id).with(authentication(admin)))
        .andExpect(status().isNotFound());

    var intruder = TenantMembers.with(db, scope, newTenant(), TenantRole.ADMIN);
    mvc.perform(get(keys(sandbox)).with(authentication(intruder)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("environment_not_found"));
    mvc.perform(get(keys(sandbox) + "/" + id).with(authentication(intruder)))
        .andExpect(status().isNotFound());
    mvc.perform(delete(keys(sandbox) + "/" + id).with(authentication(intruder)))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(keys(sandbox) + "/" + id + "/rotate")
                .with(authentication(intruder))
                .header("Idempotency-Key", "x")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    create(intruder, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:read\"]}")
        .andExpect(status().isNotFound());

    mvc.perform(get(keys(sandbox) + "/" + id).with(authentication(admin)))
        .andExpect(status().isOk());
  }

  @Test
  void an_environment_has_at_most_50_live_keys_and_expired_ones_do_not_count() throws Exception {
    UUID env = UUID.fromString(sandbox);
    for (int i = 0; i < ApiKeys.MAX_KEYS_PER_ENVIRONMENT - 1; i++) {
      String name = "k" + i;
      TestCallers.as(
          admin,
          () ->
              keys.create(
                  admin.caller(),
                  project.id(),
                  env,
                  name,
                  List.of("usage:read"),
                  Optional.empty()));
    }
    TestCallers.as(
        admin,
        () ->
            keys.create(
                admin.caller(),
                project.id(),
                env,
                "short-lived",
                List.of("usage:read"),
                Optional.of(OffsetDateTime.now(clock).plusMinutes(1))));
    create(admin, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:read\"]}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("api_key_limit_reached"));

    clock.advance(Duration.ofMinutes(2));
    create(admin, sandbox, "{\"name\":\"x\",\"scopes\":[\"usage:read\"]}")
        .andExpect(status().isCreated());

    mvc.perform(get(keys(sandbox) + "?limit=10").with(authentication(admin)))
        .andExpect(jsonPath("$.data.length()").value(10))
        .andExpect(jsonPath("$.has_more").value(true))
        .andExpect(jsonPath("$.next_cursor").exists());
  }

  @Test
  void records_when_a_key_was_last_used_at_most_once_a_minute() throws Exception {
    JsonNode key = created();
    String secret = key.get("secret").asString();
    String path = keys(sandbox) + "/" + key.get("id").asString();

    mvc.perform(get(path).with(authentication(admin)))
        .andExpect(jsonPath("$.last_used_at").doesNotExist());
    use(secret);
    String first =
        json.readTree(
                mvc.perform(get(path).with(authentication(admin)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("last_used_at")
            .asString();
    clock.advance(Duration.ofSeconds(30));
    use(secret);
    mvc.perform(get(path).with(authentication(admin)))
        .andExpect(jsonPath("$.last_used_at").value(first));
    clock.advance(Duration.ofMinutes(2));
    use(secret);
    mvc.perform(get(path).with(authentication(admin)))
        .andExpect(jsonPath("$.last_used_at").value(org.hamcrest.Matchers.not(first)));
  }
}
