package com.meterbook.identity.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Reads Meterbook's claims from a token whose signature, issuer, audience and lifetime are already
 * validated. Anything unexpected is rejected rather than guessed (fail closed).
 */
final class TokenClaims {

  /** Keycloak identity provider aliases for tenant SSO connections: {@code sso-<connection id>}. */
  static final String SSO_ALIAS_PREFIX = "sso-";

  private TokenClaims() {}

  /**
   * The tenant from the {@code organization} claim, shaped {@code {"<alias>": {"id": "<uuid>"}}}.
   * Keycloak omits the claim when the user belongs to no organization or to several, so a missing
   * claim means "no single tenant".
   */
  static Optional<UUID> tenant(Object organizationClaim) {
    if (organizationClaim == null) {
      return Optional.empty();
    }
    if (!(organizationClaim instanceof Map<?, ?> organizations) || organizations.size() != 1) {
      throw invalid("the token must name exactly one tenant");
    }
    Object organization = organizations.values().iterator().next();
    if (!(organization instanceof Map<?, ?> fields) || !(fields.get("id") instanceof String id)) {
      throw invalid("the tenant in the token has no id");
    }
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      throw invalid("the tenant id in the token is not a UUID");
    }
  }

  /** Known roles from {@code realm_access.roles}; Keycloak's built-in roles are ignored. */
  static Set<Role> roles(Object realmAccessClaim) {
    Set<Role> roles = EnumSet.noneOf(Role.class);
    if (realmAccessClaim instanceof Map<?, ?> realmAccess
        && realmAccess.get("roles") instanceof Collection<?> values) {
      for (Object value : values) {
        if (value instanceof String s) {
          Role.fromClaim(s).ifPresent(roles::add);
        }
      }
    }
    return roles;
  }

  /** The SSO connection behind an {@code idp} claim, if the alias is one of ours. */
  static Optional<UUID> ssoConnection(String identityProviderAlias) {
    if (identityProviderAlias == null || !identityProviderAlias.startsWith(SSO_ALIAS_PREFIX)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          UUID.fromString(identityProviderAlias.substring(SSO_ALIAS_PREFIX.length())));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  static UUID subject(String sub) {
    if (sub == null) {
      throw invalid("the token subject is not a user id");
    }
    try {
      return UUID.fromString(sub);
    } catch (IllegalArgumentException e) {
      throw invalid("the token subject is not a user id");
    }
  }

  private static InvalidBearerTokenException invalid(String message) {
    return new InvalidBearerTokenException(message);
  }
}
