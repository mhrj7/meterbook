package com.meterbook.identity.support;

import com.meterbook.identity.authz.TenantRole;
import com.meterbook.identity.db.Tables;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;

/** Tenant users with role bindings (ADR-0014), for tests. */
public final class TenantMembers {

  private TenantMembers() {}

  /** A new user of {@code tenant} holding {@code role}. */
  public static CallerAuthentication with(
      DSLContext db, TenantScope scope, UUID tenant, TenantRole role) {
    CallerAuthentication user = TestCallers.tenantUser(UUID.randomUUID(), tenant);
    grant(db, scope, tenant, user.caller().userId(), role);
    return user;
  }

  public static void grant(
      DSLContext db, TenantScope scope, UUID tenant, UUID user, TenantRole role) {
    scope.inTenant(
        tenant,
        () ->
            db.insertInto(Tables.ROLE_BINDINGS)
                .set(Tables.ROLE_BINDINGS.ID, UUID.randomUUID())
                .set(Tables.ROLE_BINDINGS.TENANT_ID, tenant)
                .set(Tables.ROLE_BINDINGS.ROLE, role.value())
                .set(Tables.ROLE_BINDINGS.USER_ID, user)
                .set(Tables.ROLE_BINDINGS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
  }
}
