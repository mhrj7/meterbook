package com.meterbook.identity.authz;

import static com.meterbook.identity.db.Tables.BACKFILLS;
import static com.meterbook.identity.db.Tables.TENANTS;

import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Once, at start-up: tenants created before MB-T11 had their admins only as the Keycloak realm role
 * {@code tenant-admin}, which is no longer read (ADR-0014). Each holder who is a member of a tenant
 * gets an {@code admin} binding there, recorded in that tenant's audit log as {@code system}. Safe
 * to run on several replicas at once; if Keycloak is unreachable it runs again at the next start.
 */
@Component
public class TenantAdminBackfill implements ApplicationRunner {

  static final String NAME = "tenant_admin_role_bindings";
  private static final Logger log = LoggerFactory.getLogger(TenantAdminBackfill.class);

  private final DSLContext db;
  private final TenantScope scope;
  private final Directory directory;
  private final RoleBindings bindings;
  private final Clock clock;

  public TenantAdminBackfill(
      DSLContext db, TenantScope scope, Directory directory, RoleBindings bindings, Clock clock) {
    this.db = db;
    this.scope = scope;
    this.directory = directory;
    this.bindings = bindings;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (db.fetchExists(BACKFILLS, BACKFILLS.NAME.eq(NAME))) {
      return;
    }
    Result result = backfill();
    if (result.failed() > 0) {
      // Never block start-up for this; what is missing is retried at the next start.
      log.warn(
          "tenant admin backfill incomplete: {} admin bindings added, {} tenants to retry",
          result.added(),
          result.failed());
      return;
    }
    db.insertInto(BACKFILLS)
        .set(BACKFILLS.NAME, NAME)
        .set(BACKFILLS.COMPLETED_AT, OffsetDateTime.now(clock))
        .onConflictDoNothing()
        .execute();
    log.info("tenant admin backfill complete: {} admin bindings added", result.added());
  }

  /** How a pass went: bindings added, and tenants that could not be checked. */
  record Result(int added, int failed) {}

  Result backfill() {
    List<UUID> tenants =
        scope.asPlatform(
            "find tenants created before role bindings existed",
            () -> db.select(TENANTS.ID).from(TENANTS).fetch(TENANTS.ID));
    int added = 0;
    int failed = 0;
    for (UUID tenant : tenants) {
      try {
        for (UUID member : directory.membersOf(tenant)) {
          if (directory.realmRolesOf(member).contains(Role.TENANT_ADMIN.claimValue())
              && scope.inTenant(tenant, () -> bindings.bindAdminAsSystem(tenant, member))) {
            added++;
          }
        }
      } catch (RuntimeException e) {
        failed++;
        log.warn("tenant admin backfill could not check tenant {}", tenant, e);
      }
    }
    return new Result(added, failed);
  }
}
