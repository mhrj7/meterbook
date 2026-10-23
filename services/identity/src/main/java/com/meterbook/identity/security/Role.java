package com.meterbook.identity.security;

import java.util.Optional;

/** The roles MB-T07 knows (realm roles in Keycloak). Finer-grained RBAC arrives with MB-T11. */
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
