package com.meterbook.identity.authz;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The fixed roles a tenant can bind (ADR-0014). {@code viewer} reads everything except who holds
 * which role and the audit log; {@code auditor} reads those too; {@code developer} also builds
 * (projects, environments, API keys); {@code admin} holds every tenant permission.
 */
public enum TenantRole {
  VIEWER("viewer"),
  AUDITOR("auditor"),
  DEVELOPER("developer"),
  ADMIN("admin");

  private static final Set<Permission> SENSITIVE_READS =
      EnumSet.of(Permission.ROLE_BINDINGS_READ, Permission.AUDIT_EVENTS_READ);
  private static final Set<Permission> BUILD =
      EnumSet.of(
          Permission.PROJECTS_CREATE,
          Permission.PROJECTS_UPDATE,
          Permission.ENVIRONMENTS_CREATE,
          Permission.API_KEYS_CREATE,
          Permission.API_KEYS_REVOKE,
          Permission.API_KEYS_ROTATE);

  private final String value;

  TenantRole(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public Set<Permission> permissions() {
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    for (Permission p : Permission.values()) {
      if (!p.isPlatform() && grants(p)) {
        out.add(p);
      }
    }
    return out;
  }

  private boolean grants(Permission p) {
    return switch (this) {
      case ADMIN -> true;
      case DEVELOPER -> (p.isRead() && !SENSITIVE_READS.contains(p)) || BUILD.contains(p);
      case AUDITOR -> p.isRead();
      case VIEWER -> p.isRead() && !SENSITIVE_READS.contains(p);
    };
  }

  public static Optional<TenantRole> of(String value) {
    return Arrays.stream(values()).filter(r -> r.value.equals(value)).findFirst();
  }

  /** Everything the roles allow together. */
  public static Set<Permission> permissionsOf(Set<TenantRole> roles) {
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    roles.forEach(r -> out.addAll(r.permissions()));
    return out;
  }
}
