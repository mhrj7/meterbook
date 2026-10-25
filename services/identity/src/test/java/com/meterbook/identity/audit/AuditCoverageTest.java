package com.meterbook.identity.audit;

import static com.meterbook.identity.db.Tables.AUDIT_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.db.tables.records.AuditEventsRecord;
import com.meterbook.identity.scim.ScimTokens;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.sso.SigningCertificateTest;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * TC-PLT-011: every change made through the API or SCIM records exactly one audit event, with the
 * right action, target, actor and request ID (ADR-0013). TC-PLT-002: a cross-tenant attempt is
 * recorded in the caller's own log, and reveals nothing about the other tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, AuditCoverageTest.Fixture.class})
class AuditCoverageTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  private static final String SCIM = "application/scim+json";
  private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

  @Autowired MockMvc mvc;
  @Autowired FakeDirectory directory;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;
  @Autowired ScimTokens tokens;

  private UUID tenant;
  private String domain;
  private CallerAuthentication admin;
  private String scimToken;
  private UUID scimTokenId;

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
    admin = TestCallers.tenantUser(UUID.randomUUID(), tenant, Role.TENANT_ADMIN);
    var issued = TestCallers.as(admin, () -> tokens.issue(admin.caller(), "audit test"));
    scimToken = issued.secret();
    scimTokenId = issued.token().id();
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

  /** What one call must have recorded. */
  record Expected(MvcResult result, UUID tenant, UUID target, String actorType, UUID actorId) {}

  /** One mutating endpoint: prepares what it needs, then makes the call under test. */
  @FunctionalInterface
  interface Change {
    Expected call(AuditCoverageTest test) throws Exception;
  }

  static Stream<Arguments> changes() {
    return Stream.of(
        arguments(AuditAction.TENANT_CREATED, (Change) AuditCoverageTest::createTenant),
        arguments(AuditAction.SSO_CONNECTION_CREATED, (Change) AuditCoverageTest::createSso),
        arguments(AuditAction.SSO_CONNECTION_DELETED, (Change) AuditCoverageTest::deleteSso),
        arguments(AuditAction.PROJECT_CREATED, (Change) AuditCoverageTest::createProject),
        arguments(AuditAction.PROJECT_UPDATED, (Change) AuditCoverageTest::updateProject),
        arguments(AuditAction.ENVIRONMENT_CREATED, (Change) AuditCoverageTest::createEnvironment),
        arguments(AuditAction.API_KEY_CREATED, (Change) AuditCoverageTest::createKey),
        arguments(AuditAction.API_KEY_REVOKED, (Change) AuditCoverageTest::revokeKey),
        arguments(AuditAction.API_KEY_ROTATED, (Change) AuditCoverageTest::rotateKey),
        arguments(AuditAction.SCIM_TOKEN_CREATED, (Change) AuditCoverageTest::createScimToken),
        arguments(AuditAction.SCIM_TOKEN_REVOKED, (Change) AuditCoverageTest::revokeScimToken),
        arguments(AuditAction.SCIM_USER_CREATED, (Change) AuditCoverageTest::createUser),
        arguments(AuditAction.SCIM_USER_REPLACED, (Change) AuditCoverageTest::replaceUser),
        arguments(AuditAction.SCIM_USER_UPDATED, (Change) AuditCoverageTest::patchUser),
        arguments(AuditAction.SCIM_USER_DELETED, (Change) AuditCoverageTest::deleteUser),
        arguments(AuditAction.SCIM_GROUP_CREATED, (Change) AuditCoverageTest::createGroup),
        arguments(AuditAction.SCIM_GROUP_UPDATED, (Change) AuditCoverageTest::patchGroup),
        arguments(AuditAction.SCIM_GROUP_DELETED, (Change) AuditCoverageTest::deleteGroup));
  }

  @Test
  void every_kind_of_change_is_exercised_below() {
    Set<AuditAction> exercised =
        changes().map(a -> (AuditAction) a.get()[0]).collect(Collectors.toSet());
    Set<AuditAction> all = EnumSet.noneOf(AuditAction.class);
    Arrays.stream(AuditAction.values()).filter(AuditAction::isChange).forEach(all::add);
    assertThat(exercised).isEqualTo(all);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("changes")
  void every_change_records_exactly_one_success_event(AuditAction action, Change change)
      throws Exception {
    Expected expected = change.call(this);
    int status = expected.result().getResponse().getStatus();
    assertThat(status).as("the call itself succeeds").isBetween(200, 299);

    List<AuditEventsRecord> events = eventsOf(expected.tenant(), expected.result());
    assertThat(events).hasSize(1);
    AuditEventsRecord event = events.getFirst();
    assertThat(event.getAction()).isEqualTo(action.code());
    assertThat(event.getOutcome()).isEqualTo("success");
    assertThat(event.getTargetType()).isEqualTo(action.targetType());
    assertThat(event.getTargetId()).isEqualTo(expected.target());
    assertThat(event.getActorType()).isEqualTo(expected.actorType());
    assertThat(event.getActorId()).isEqualTo(expected.actorId());
    // Events never carry what was sent: no secrets, no names, no emails.
    assertThat(event.getDetails().data()).doesNotContain("@", "mb_", "mbscim_", "Renamed", "Jane");
  }

  @Test
  void an_idempotent_replay_records_nothing_new() throws Exception {
    String body = "{\"name\":\"Checkout\",\"slug\":\"checkout\"}";
    MvcResult first = call(asAdmin(post("/v1/projects")).header("Idempotency-Key", "k1"), body);
    MvcResult replay = call(asAdmin(post("/v1/projects")).header("Idempotency-Key", "k1"), body);

    assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(eventsOf(tenant, first)).hasSize(1);
    assertThat(eventsOf(tenant, replay)).isEmpty();
  }

  @Test
  void a_refused_change_is_recorded_as_denied_and_changes_nothing() throws Exception {
    CallerAuthentication member = TestCallers.tenantUser(UUID.randomUUID(), tenant);
    MvcResult result =
        call(
            post("/v1/projects")
                .with(authentication(member))
                .header("Idempotency-Key", "k")
                .contentType("application/json"),
            "{\"name\":\"Checkout\",\"slug\":\"checkout\"}");

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(eventsOf(tenant, result))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getAction()).isEqualTo("project.created");
              assertThat(e.getOutcome()).isEqualTo("denied");
              assertThat(e.getActorId()).isEqualTo(member.caller().userId());
              assertThat(e.getTargetId()).isNull();
            });
    assertThat(scope.inTenant(tenant, () -> db.fetchCount(Tables.PROJECTS))).isZero();
  }

  @Test
  void a_cross_tenant_attempt_is_recorded_in_the_callers_log_only() throws Exception {
    UUID project = id(createProject().result());
    UUID other = newTenant();
    CallerAuthentication intruder =
        TestCallers.tenantUser(UUID.randomUUID(), other, Role.TENANT_ADMIN);

    MvcResult read =
        mvc.perform(get("/v1/projects/" + project).with(authentication(intruder))).andReturn();
    MvcResult write =
        call(
            patch("/v1/projects/" + project)
                .with(authentication(intruder))
                .contentType("application/json"),
            "{\"name\":\"Owned\"}");
    UUID missing = UUID.randomUUID();
    MvcResult nothing =
        mvc.perform(get("/v1/projects/" + missing).with(authentication(intruder))).andReturn();

    for (MvcResult result : List.of(read, write, nothing)) {
      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertThat(eventsOf(tenant, result)).as("nothing in the target tenant's log").isEmpty();
      assertThat(eventsOf(other, result))
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.getOutcome()).isEqualTo("not_found");
                assertThat(e.getActorId()).isEqualTo(intruder.caller().userId());
                assertThat(e.getTargetType()).isEqualTo("project");
              });
    }
    assertThat(eventsOf(other, read).getFirst().getAction()).isEqualTo("project.read");
    assertThat(eventsOf(other, write).getFirst().getAction()).isEqualTo("project.updated");
    // An existing and a missing project look the same, in the response and in the log.
    assertThat(eventsOf(other, read).getFirst().getTargetId()).isEqualTo(project);
    assertThat(eventsOf(other, nothing).getFirst().getTargetId()).isEqualTo(missing);
    assertThat(withoutRequestId(read)).isEqualTo(withoutRequestId(nothing));
  }

  @Test
  void a_scim_client_reaching_into_another_tenant_is_recorded_in_its_own_log() throws Exception {
    UUID user = id(createUser().result());
    UUID other = newTenant();
    String otherToken =
        TestCallers.as(
                admin,
                () ->
                    tokens.issue(
                        TestCallers.tenantUser(UUID.randomUUID(), other, Role.TENANT_ADMIN)
                            .caller(),
                        "other"))
            .secret();

    MvcResult result =
        mvc.perform(get("/scim/v2/Users/" + user).header("Authorization", "Bearer " + otherToken))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(eventsOf(other, result))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getAction()).isEqualTo("scim_user.read");
              assertThat(e.getOutcome()).isEqualTo("not_found");
              assertThat(e.getActorType()).isEqualTo("scim_token");
              assertThat(e.getTargetId()).isEqualTo(user);
            });
  }

  @Test
  void invalid_requests_record_nothing() throws Exception {
    MvcResult result =
        call(asAdmin(post("/v1/projects")).header("Idempotency-Key", "k"), "{\"name\":\"\"}");

    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(eventsOf(tenant, result)).isEmpty();
  }

  // The changes, one per mutating endpoint.

  Expected createTenant() throws Exception {
    CallerAuthentication operator = TestCallers.platformAdmin(UUID.randomUUID());
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    MvcResult result =
        call(
            post("/v1/tenants")
                .with(authentication(operator))
                .header("Idempotency-Key", "k")
                .contentType("application/json"),
            """
            {"name":"Acme","slug":"acme-%s","domains":["acme-%s.test"],"initial_admin_email":"a@acme-%s.test"}"""
                .formatted(suffix, suffix, suffix));
    UUID created = id(result);
    return new Expected(result, created, created, "platform_operator", operator.caller().userId());
  }

  Expected createSso() throws Exception {
    MvcResult result =
        call(asAdmin(post("/v1/sso-connections")).header("Idempotency-Key", "k"), ssoBody());
    return byAdmin(result, id(result));
  }

  Expected deleteSso() throws Exception {
    UUID connection = id(createSso().result());
    return byAdmin(call(asAdmin(delete("/v1/sso-connections/" + connection)), null), connection);
  }

  Expected createProject() throws Exception {
    MvcResult result =
        call(
            asAdmin(post("/v1/projects")).header("Idempotency-Key", UUID.randomUUID().toString()),
            "{\"name\":\"Checkout\",\"slug\":\"p-"
                + UUID.randomUUID().toString().substring(0, 8)
                + "\"}");
    return byAdmin(result, id(result));
  }

  Expected updateProject() throws Exception {
    UUID project = id(createProject().result());
    return byAdmin(
        call(asAdmin(patch("/v1/projects/" + project)), "{\"name\":\"Renamed\"}"), project);
  }

  Expected createEnvironment() throws Exception {
    UUID project = id(createProject().result());
    MvcResult result =
        call(
            asAdmin(post("/v1/projects/" + project + "/environments"))
                .header("Idempotency-Key", "k"),
            "{\"name\":\"Staging\",\"slug\":\"staging\",\"kind\":\"test\"}");
    return byAdmin(result, id(result));
  }

  private String keysPath() throws Exception {
    var project = json.readTree(createProject().result().getResponse().getContentAsString());
    return "/v1/projects/"
        + project.get("id").asString()
        + "/environments/"
        + project.get("environments").get(1).get("id").asString()
        + "/api-keys";
  }

  private MvcResult createKeyAt(String path) throws Exception {
    return call(
        asAdmin(post(path)).header("Idempotency-Key", UUID.randomUUID().toString()),
        "{\"name\":\"Billing job\",\"scopes\":[\"usage:read\"]}");
  }

  Expected createKey() throws Exception {
    MvcResult result = createKeyAt(keysPath());
    return byAdmin(result, id(result));
  }

  Expected revokeKey() throws Exception {
    String path = keysPath();
    UUID key = id(createKeyAt(path));
    return byAdmin(call(asAdmin(delete(path + "/" + key)), null), key);
  }

  Expected rotateKey() throws Exception {
    String path = keysPath();
    UUID key = id(createKeyAt(path));
    MvcResult result =
        call(asAdmin(post(path + "/" + key + "/rotate")).header("Idempotency-Key", "k"), "{}");
    return byAdmin(result, id(result));
  }

  Expected createScimToken() throws Exception {
    MvcResult result =
        call(
            asAdmin(post("/v1/scim-tokens")).header("Idempotency-Key", "k"),
            "{\"description\":\"Okta\"}");
    return byAdmin(result, id(result));
  }

  Expected revokeScimToken() throws Exception {
    UUID token = id(createScimToken().result());
    return byAdmin(call(asAdmin(delete("/v1/scim-tokens/" + token)), null), token);
  }

  private String userBody(String local) {
    return """
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s@%s",
         "name":{"givenName":"Jane","familyName":"Doe"},"active":true,
         "emails":[{"value":"%s@%s","type":"work","primary":true}]}"""
        .formatted(local, domain, local, domain);
  }

  Expected createUser() throws Exception {
    MvcResult result =
        call(
            scim(post("/scim/v2/Users")),
            userBody("u" + UUID.randomUUID().toString().substring(0, 8)));
    return byScim(result, id(result));
  }

  Expected replaceUser() throws Exception {
    MvcResult created = createUser().result();
    UUID user = id(created);
    String userName =
        json.readTree(created.getResponse().getContentAsString()).get("userName").asString();
    return byScim(
        call(
            scim(put("/scim/v2/Users/" + user)),
            userBody(userName.substring(0, userName.indexOf('@')))),
        user);
  }

  Expected patchUser() throws Exception {
    UUID user = id(createUser().result());
    MvcResult result =
        call(
            scim(patch("/scim/v2/Users/" + user)),
            "{\"schemas\":[\""
                + PATCH_OP
                + "\"],\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}");
    Expected expected = byScim(result, user);
    assertThat(eventsOf(tenant, result).getFirst().getDetails().data()).contains("\"active\"");
    return expected;
  }

  Expected deleteUser() throws Exception {
    UUID user = id(createUser().result());
    return byScim(call(scim(delete("/scim/v2/Users/" + user)), null), user);
  }

  Expected createGroup() throws Exception {
    MvcResult result =
        call(
            scim(post("/scim/v2/Groups")),
            "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\"g-"
                + UUID.randomUUID().toString().substring(0, 8)
                + "\"}");
    return byScim(result, id(result));
  }

  Expected patchGroup() throws Exception {
    UUID group = id(createGroup().result());
    return byScim(
        call(
            scim(patch("/scim/v2/Groups/" + group)),
            "{\"schemas\":[\""
                + PATCH_OP
                + "\"],\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Finance\"}]}"),
        group);
  }

  Expected deleteGroup() throws Exception {
    UUID group = id(createGroup().result());
    return byScim(call(scim(delete("/scim/v2/Groups/" + group)), null), group);
  }

  // Helpers.

  private static String ssoBody() {
    return """
        {"display_name":"Acme Okta","protocol":"saml","saml":{"entity_id":"http://www.okta.com/exk1","sso_url":"https://acme.okta.com/app/sso/saml","signing_certificate":%s}}"""
        .formatted(
            JsonMapper.builder()
                .build()
                .writeValueAsString(SigningCertificateTest.pem("idp-valid.crt")));
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.with(authentication(admin)).contentType("application/json");
  }

  private MockHttpServletRequestBuilder scim(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + scimToken).contentType(SCIM);
  }

  private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
    return mvc.perform(body == null ? request : request.content(body)).andReturn();
  }

  private Expected byAdmin(MvcResult result, UUID target) {
    return new Expected(result, tenant, target, "user", admin.caller().userId());
  }

  private Expected byScim(MvcResult result, UUID target) {
    return new Expected(result, tenant, target, "scim_token", scimTokenId);
  }

  private UUID id(MvcResult result) throws Exception {
    return UUID.fromString(
        json.readTree(result.getResponse().getContentAsString()).get("id").asString());
  }

  /** The events one request left in a tenant's log, found by its request ID. */
  private List<AuditEventsRecord> eventsOf(UUID tenant, MvcResult result) {
    String requestId = result.getResponse().getHeader("Request-Id");
    assertThat(requestId).isNotBlank();
    return scope.inTenant(
        tenant,
        () -> db.selectFrom(AUDIT_EVENTS).where(AUDIT_EVENTS.REQUEST_ID.eq(requestId)).fetch());
  }

  private Map<String, Object> withoutRequestId(MvcResult result) throws Exception {
    var body = json.readTree(result.getResponse().getContentAsString()).get("error");
    return Map.of(
        "status",
        result.getResponse().getStatus(),
        "code",
        body.get("code").asString(),
        "message",
        body.get("message").asString());
  }
}
