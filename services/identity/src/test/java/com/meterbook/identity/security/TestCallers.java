package com.meterbook.identity.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
