package com.meterbook.identity.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling, taken only from a validated access token.
 *
 * @param userId Keycloak user ID ({@code sub})
 * @param tenantId the single organization the token names; empty only for platform operators
 * @param ssoConnectionId the SSO connection used to sign in, when known
 */
public record Caller(
    UUID userId,
    Optional<UUID> tenantId,
    String email,
    String name,
    Set<Role> roles,
    LoginMethod loginMethod,
    Optional<UUID> ssoConnectionId) {

  /** How the user signed in. */
  public enum LoginMethod {
    PASSWORD,
    SSO
  }

  public Caller {
    roles = Set.copyOf(roles);
  }

  public boolean has(Role role) {
    return roles.contains(role);
  }
}
