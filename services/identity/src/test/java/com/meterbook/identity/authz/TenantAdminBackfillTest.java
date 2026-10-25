package com.meterbook.identity.authz;

import static com.meterbook.identity.db.Tables.AUDIT_EVENTS;
import static com.meterbook.identity.db.Tables.BACKFILLS;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterbook.identity.audit.AuditLog;
import com.meterbook.identity.db.Tables;
import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Tenants from before MB-T11 keep their admins, who become role bindings once (ADR-0014). */
@SpringBootTest
@Import({TestDatabase.class, TenantAdminBackfillTest.Fixture.class})
class TenantAdminBackfillTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  @Autowired TenantAdminBackfill backfill;
  @Autowired RoleBindings bindings;
  @Autowired FakeDirectory directory;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;

  private UUID tenant;
  private UUID legacyAdmin;
  private UUID member;

  @BeforeEach
  void legacyTenant() {
    directory.reset();
    db.deleteFrom(BACKFILLS).where(BACKFILLS.NAME.eq(TenantAdminBackfill.NAME)).execute();
    tenant = UUID.randomUUID();
    scope.inTenant(
        tenant,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, tenant)
                .set(Tables.TENANTS.SLUG, "t-" + tenant.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Before MB-T11")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    legacyAdmin = UUID.randomUUID();
    member = UUID.randomUUID();
    directory.addMember(tenant, legacyAdmin);
    directory.addMember(tenant, member);
    directory.roles.put(legacyAdmin, new java.util.HashSet<>(Set.of("tenant-admin")));
  }

  private void run() {
    backfill.run(new DefaultApplicationArguments());
  }

  @Test
  void holders_of_the_old_realm_role_become_admins_once_recorded_as_system() {
    assertThat(backfill.backfill()).isEqualTo(new TenantAdminBackfill.Result(1, 0));
    run();

    assertThat(bindings.rolesOf(tenant, legacyAdmin)).containsExactly(TenantRole.ADMIN);
    assertThat(bindings.rolesOf(tenant, member)).isEmpty();
    var event = scope.inTenant(tenant, () -> db.selectFrom(AUDIT_EVENTS).fetchSingle());
    assertThat(event.getAction()).isEqualTo("role_binding.created");
    assertThat(event.getActorType()).isEqualTo("system");
    assertThat(event.getActorId()).isEqualTo(AuditLog.SYSTEM);
    assertThat(event.getDetails().data()).contains("tenant_admin_backfill");
    assertThat(db.fetchExists(BACKFILLS, BACKFILLS.NAME.eq(TenantAdminBackfill.NAME))).isTrue();

    // Done means done: later starts change nothing, even for a new realm-role holder.
    directory.roles.put(member, new java.util.HashSet<>(Set.of("tenant-admin")));
    run();
    assertThat(bindings.rolesOf(tenant, member)).isEmpty();
  }

  @Test
  void an_admin_who_already_has_a_binding_is_left_alone() {
    scope.inTenant(tenant, () -> bindings.bindAdminAsSystem(tenant, legacyAdmin));
    assertThat(backfill.backfill().added()).isZero();
    assertThat(scope.inTenant(tenant, () -> db.fetchCount(AUDIT_EVENTS))).isEqualTo(1);
  }

  @Test
  void one_tenant_that_cannot_be_checked_does_not_stop_the_others() {
    UUID broken = UUID.randomUUID();
    scope.inTenant(
        broken,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, broken)
                .set(Tables.TENANTS.SLUG, "t-" + broken.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Organization deleted by hand")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    directory.onCall(
        "membersOf",
        () -> {
          throw new IllegalStateException("404 from Keycloak");
        });

    var result = backfill.backfill();
    assertThat(result.failed()).isPositive();
    run();
    assertThat(db.fetchExists(BACKFILLS, BACKFILLS.NAME.eq(TenantAdminBackfill.NAME))).isFalse();
  }

  @Test
  void without_keycloak_it_waits_for_the_next_start() {
    directory.failOn("membersOf", new Directory.UnavailableException("down", null));
    run();

    assertThat(db.fetchExists(BACKFILLS, BACKFILLS.NAME.eq(TenantAdminBackfill.NAME))).isFalse();
    assertThat(bindings.rolesOf(tenant, legacyAdmin)).isEmpty();

    directory.reset();
    directory.addMember(tenant, legacyAdmin);
    directory.roles.put(legacyAdmin, new java.util.HashSet<>(Set.of("tenant-admin")));
    run();
    assertThat(bindings.rolesOf(tenant, legacyAdmin)).containsExactly(TenantRole.ADMIN);
  }
}
