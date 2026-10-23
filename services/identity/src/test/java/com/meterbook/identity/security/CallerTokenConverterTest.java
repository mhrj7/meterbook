package com.meterbook.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class CallerTokenConverterTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();

  private final CallerTokenConverter converter = new CallerTokenConverter();

  private static Jwt.Builder token() {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .subject(USER.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300));
  }

  @Test
  void a_tenant_user_signed_in_with_a_password() {
    Jwt jwt =
        token()
            .claim("organization", Map.of("acme", Map.of("id", TENANT.toString())))
            .claim("realm_access", Map.of("roles", List.of("tenant-admin")))
            .claim("email", "jane@acme.test")
            .claim("name", "Jane Doe")
            .build();

    CallerAuthentication auth = converter.convert(jwt);

    assertThat(auth.caller())
        .isEqualTo(
            new Caller(
                USER,
                java.util.Optional.of(TENANT),
                "jane@acme.test",
                "Jane Doe",
                java.util.Set.of(Role.TENANT_ADMIN),
                Caller.LoginMethod.PASSWORD,
                java.util.Optional.empty()));
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_TENANT_ADMIN");
    assertThat(auth.getName()).isEqualTo(USER.toString());
    assertThat(auth.getPrincipal()).isSameAs(auth.caller());
    assertThat(auth.getCredentials()).isSameAs(jwt);
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.caller().has(Role.TENANT_ADMIN)).isTrue();
    assertThat(auth.caller().has(Role.PLATFORM_ADMIN)).isFalse();
  }

  @Test
  void a_user_signed_in_through_a_tenant_sso_connection() {
    UUID connection = UUID.randomUUID();
    Jwt jwt =
        token()
            .claim("organization", Map.of("acme", Map.of("id", TENANT.toString())))
            .claim("idp", "sso-" + connection)
            .build();

    Caller caller = converter.convert(jwt).caller();

    assertThat(caller.loginMethod()).isEqualTo(Caller.LoginMethod.SSO);
    assertThat(caller.ssoConnectionId()).contains(connection);
    assertThat(caller.email()).isEmpty();
    assertThat(caller.name()).isEmpty();
  }

  @Test
  void a_platform_operator_needs_no_tenant() {
    Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("platform-admin"))).build();

    Caller caller = converter.convert(jwt).caller();

    assertThat(caller.tenantId()).isEmpty();
    assertThat(caller.has(Role.PLATFORM_ADMIN)).isTrue();
  }

  @Test
  void anyone_else_without_a_single_tenant_is_rejected() {
    assertThatThrownBy(() -> converter.convert(token().build()))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void authentications_are_equal_when_their_callers_are() {
    Jwt jwt =
        token().claim("organization", Map.of("acme", Map.of("id", TENANT.toString()))).build();

    CallerAuthentication a = converter.convert(jwt);
    CallerAuthentication b = converter.convert(jwt);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo("other");
  }
}
