package com.meterbook.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The role matrix, written out (ADR-0014): a new permission or a changed rule fails here until the
 * table is updated on purpose.
 */
class TenantRoleTest {

  private static final Set<Permission> READS =
      EnumSet.of(
          Permission.TENANTS_READ,
          Permission.SSO_CONNECTIONS_READ,
          Permission.PROJECTS_READ,
          Permission.ENVIRONMENTS_READ,
          Permission.API_KEYS_READ,
          Permission.SCIM_TOKENS_READ,
          Permission.ROLES_READ);

  private static Set<Permission> plus(Set<Permission> base, Permission... more) {
    Set<Permission> out = EnumSet.copyOf(base);
    out.addAll(Set.of(more));
    return out;
  }

  @Test
  void each_role_holds_exactly_its_permissions() {
    Set<Permission> admin = EnumSet.allOf(Permission.class);
    admin.remove(Permission.TENANTS_CREATE);
    Map<TenantRole, Set<Permission>> expected =
        Map.of(
            TenantRole.VIEWER,
            READS,
            TenantRole.AUDITOR,
            plus(READS, Permission.ROLE_BINDINGS_READ, Permission.AUDIT_EVENTS_READ),
            TenantRole.DEVELOPER,
            plus(
                READS,
                Permission.PROJECTS_CREATE,
                Permission.PROJECTS_UPDATE,
                Permission.ENVIRONMENTS_CREATE,
                Permission.API_KEYS_CREATE,
                Permission.API_KEYS_REVOKE,
                Permission.API_KEYS_ROTATE),
            TenantRole.ADMIN,
            admin);

    for (TenantRole role : TenantRole.values()) {
      assertThat(role.permissions()).as(role.value()).isEqualTo(expected.get(role));
    }
  }

  @Test
  void no_tenant_role_creates_tenants() {
    for (TenantRole role : TenantRole.values()) {
      assertThat(role.permissions()).doesNotContain(Permission.TENANTS_CREATE);
    }
  }

  @Test
  void roles_combine_and_are_found_by_name() {
    assertThat(TenantRole.permissionsOf(Set.of(TenantRole.AUDITOR, TenantRole.DEVELOPER)))
        .contains(Permission.AUDIT_EVENTS_READ, Permission.API_KEYS_CREATE)
        .doesNotContain(Permission.ROLE_BINDINGS_CREATE);
    assertThat(TenantRole.permissionsOf(Set.of())).isEmpty();
    assertThat(TenantRole.of("auditor")).contains(TenantRole.AUDITOR);
    assertThat(TenantRole.of("owner")).isEmpty();
  }
}
