package com.meterbook.identity.audit;

import static com.meterbook.identity.db.Tables.AUDIT_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterbook.identity.authz.TenantRole;
import com.meterbook.identity.db.Tables;
import com.meterbook.identity.scim.ScimCallers;
import com.meterbook.identity.scim.ScimTokens;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.TenantMembers;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.RequestIdFilter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/** The audit log at the database: append-only and tenant-isolated, whoever asks (ADR-0013). */
@SpringBootTest
@Import(TestDatabase.class)
class AuditLogTest {

  @Autowired AuditLog audit;
  @Autowired TenantScope scope;
  @Autowired DSLContext db;
  @Autowired ScimTokens tokens;

  private UUID a;
  private UUID b;
  private CallerAuthentication admin;

  @BeforeEach
  void tenants() {
    a = newTenant();
    b = newTenant();
    admin = TenantMembers.with(db, scope, a, TenantRole.ADMIN);
    MDC.put(RequestIdFilter.MDC_KEY, "req_test");
  }

  @AfterEach
  void clearRequest() {
    MDC.remove(RequestIdFilter.MDC_KEY);
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

  private void recordIn(UUID tenant) {
    TestCallers.as(
        admin,
        () -> {
          scope.inTenant(
              tenant, () -> audit.record(tenant, AuditAction.PROJECT_CREATED, UUID.randomUUID()));
          return null;
        });
  }

  @Test
  void an_event_carries_the_actor_and_request_id_of_the_request() {
    recordIn(a);

    var event = scope.inTenant(a, () -> db.selectFrom(AUDIT_EVENTS).fetchSingle());
    assertThat(event.getActorType()).isEqualTo("user");
    assertThat(event.getActorId()).isEqualTo(admin.caller().userId());
    assertThat(event.getRequestId()).isEqualTo("req_test");
    assertThat(event.getDetails().data()).isEqualTo("{}");
  }

  @Test
  void without_a_request_the_event_says_so() {
    MDC.remove(RequestIdFilter.MDC_KEY);
    recordIn(a);

    assertThat(scope.inTenant(a, () -> db.fetchValue(AUDIT_EVENTS.REQUEST_ID)))
        .isEqualTo(AuditLog.NO_REQUEST);
  }

  @Test
  void details_are_stored_as_one_json_object() {
    TestCallers.as(
        admin,
        () ->
            scope.inTenant(
                a,
                () -> {
                  audit.record(
                      a,
                      AuditAction.PROJECT_UPDATED,
                      UUID.randomUUID(),
                      Map.of("z", "1", "a", "2"));
                  return null;
                }));

    assertThat(scope.inTenant(a, () -> db.fetchValue(AUDIT_EVENTS.DETAILS)).data())
        .isEqualTo("{\"a\": \"2\", \"z\": \"1\"}");
  }

  @Test
  void a_tenant_reads_only_its_own_events() {
    recordIn(a);
    recordIn(b);

    assertThat(scope.inTenant(a, () -> db.fetch(AUDIT_EVENTS).map(r -> r.getTenantId())))
        .containsExactly(a);
    assertThat(scope.asPlatform("test", () -> db.fetchCount(AUDIT_EVENTS))).isZero();
  }

  @Test
  void a_tenant_cannot_write_events_into_another_tenants_log() {
    assertThatThrownBy(
            () ->
                TestCallers.as(
                    admin,
                    () ->
                        scope.inTenant(
                            a,
                            () -> {
                              audit.record(b, AuditAction.PROJECT_CREATED, UUID.randomUUID());
                              return null;
                            })))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("violates row-level security policy");
  }

  @Test
  void events_cannot_be_changed_or_removed_even_in_their_own_tenant() {
    recordIn(a);

    // Row-level security has no UPDATE or DELETE policy: nothing matches.
    assertThat(
            scope.inTenant(
                a, () -> db.update(AUDIT_EVENTS).set(AUDIT_EVENTS.ACTION, "x.y").execute()))
        .isZero();
    assertThat(scope.inTenant(a, () -> db.deleteFrom(AUDIT_EVENTS).execute())).isZero();
    // And the trigger refuses outright, for the owner too.
    assertThatThrownBy(() -> scope.inTenant(a, () -> db.truncate(AUDIT_EVENTS).execute()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("audit_events is append-only (TRUNCATE)");
    assertThat(scope.inTenant(a, () -> db.fetchCount(AUDIT_EVENTS))).isEqualTo(1);
  }

  @Test
  void the_trigger_refuses_deletes_even_with_row_level_security_off() {
    recordIn(a);
    // The service role owns the table, so it could turn row-level security off; the trigger stays.
    // The transaction is rolled back at the end, so the table is protected again afterwards.
    assertThatThrownBy(
            () ->
                scope.inTenant(
                    a,
                    () -> {
                      db.execute("ALTER TABLE audit_events NO FORCE ROW LEVEL SECURITY");
                      db.execute("ALTER TABLE audit_events DISABLE ROW LEVEL SECURITY");
                      return db.deleteFrom(AUDIT_EVENTS).execute();
                    }))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("audit_events is append-only (DELETE)");
    assertThat(
            db.fetchValue(
                "select relforcerowsecurity from pg_class where relname = 'audit_events'"))
        .isEqualTo(true);
  }

  @Test
  void a_change_is_recorded_only_inside_its_transaction() {
    assertThatThrownBy(
            () ->
                TestCallers.as(
                    admin,
                    () -> {
                      audit.record(a, AuditAction.PROJECT_CREATED, UUID.randomUUID());
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction of its change");
  }

  @Test
  void only_changes_are_recorded_as_successes() {
    assertThatThrownBy(() -> audit.record(a, AuditAction.PROJECT_READ, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_change_needs_an_authenticated_actor() {
    assertThatThrownBy(
            () ->
                scope.inTenant(
                    a, () -> audit.record(a, AuditAction.PROJECT_CREATED, UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authenticated actor");
  }

  @Test
  void operators_and_scim_clients_are_named_as_such() {
    var operator = TestCallers.platformAdmin(UUID.randomUUID());
    TestCallers.as(
        operator,
        () ->
            scope.inTenant(
                a,
                () -> {
                  audit.record(a, AuditAction.TENANT_CREATED, a);
                  return null;
                }));
    var token = TestCallers.as(admin, () -> tokens.issue(admin.caller(), "t"));
    var scim = tokens.authenticate(token.secret()).orElseThrow();
    TestCallers.as(
        ScimCallers.of(scim),
        () ->
            scope.inTenant(
                a,
                () -> {
                  audit.record(a, AuditAction.SCIM_GROUP_CREATED, UUID.randomUUID());
                  return null;
                }));

    var actors =
        scope.inTenant(
            a,
            () ->
                db.select(AUDIT_EVENTS.ACTION, AUDIT_EVENTS.ACTOR_TYPE, AUDIT_EVENTS.ACTOR_ID)
                    .from(AUDIT_EVENTS)
                    .fetchMap(AUDIT_EVENTS.ACTION, r -> r.value2() + ":" + r.value3()));
    assertThat(actors)
        .containsEntry("tenant.created", "platform_operator:" + operator.caller().userId())
        .containsEntry("scim_group.created", "scim_token:" + token.token().id());
  }
}
