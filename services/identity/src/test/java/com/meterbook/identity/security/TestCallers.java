package com.meterbook.identity.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authenticated callers for tests that do not need a real Keycloak. */
public final class TestCallers {

  private TestCallers() {}

  public static CallerAuthentication tenantUser(UUID user, UUID tenant, Role... roles) {
    return of(
        new Caller(
            user,
            Optional.of(tenant),
            "user@test",
            "Test User",
            Set.of(roles),
            Caller.LoginMethod.PASSWORD,
            Optional.empty()));
  }

  public static CallerAuthentication platformAdmin(UUID user) {
    return of(
        new Caller(
            user,
            Optional.empty(),
            "",
            "",
            Set.of(Role.PLATFORM_ADMIN),
            Caller.LoginMethod.PASSWORD,
            Optional.empty()));
  }

  /**
   * Runs {@code work} as if {@code authentication} had made the request, for tests that call
   * services directly: audited changes need an actor (ADR-0013).
   */
  public static <T> T as(Authentication authentication, Supplier<T> work) {
    var previous = SecurityContextHolder.getContext().getAuthentication();
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.getContext().setAuthentication(previous);
    }
  }

  private static CallerAuthentication of(Caller caller) {
    Jwt jwt =
        Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject(caller.userId().toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return new CallerAuthentication(caller, jwt);
  }
}
