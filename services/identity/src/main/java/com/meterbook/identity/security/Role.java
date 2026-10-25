package com.meterbook.identity.security;

import java.util.Optional;

/**
 * Keycloak realm roles in the access token. Only {@code platform-admin} authorizes anything; tenant
 * roles are role bindings (ADR-0014), and {@code tenant-admin} is read once, by the backfill of
 * tenants created before them.
 */
public enum Role {
  PLATFORM_ADMIN("platform-admin"),
  TENANT_ADMIN("tenant-admin");

  private final String claimValue;

  Role(String claimValue) {
    this.claimValue = claimValue;
  }

  public String claimValue() {
    return claimValue;
  }

  public String authority() {
    return "ROLE_" + name();
  }

  public static Optional<Role> fromClaim(String value) {
    for (Role role : values()) {
      if (role.claimValue.equals(value)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }
}
