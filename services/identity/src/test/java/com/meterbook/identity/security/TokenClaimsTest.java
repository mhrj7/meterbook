package com.meterbook.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class TokenClaimsTest {

  private static final UUID ID = UUID.fromString("ef99ffba-364d-49a7-af88-ddbd058cff88");

  @Test
  void reads_the_single_tenant_id() {
    assertThat(TokenClaims.tenant(Map.of("acme", Map.of("id", ID.toString())))).contains(ID);
  }

  @Test
  void a_missing_claim_means_no_tenant() {
    assertThat(TokenClaims.tenant(null)).isEmpty();
  }

  @Test
  void rejects_tokens_naming_several_or_no_tenants() {
    Map<String, Object> two =
        Map.of(
            "acme",
            Map.of("id", ID.toString()),
            "globex",
            Map.of("id", UUID.randomUUID().toString()));

    assertThatThrownBy(() -> TokenClaims.tenant(two))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.tenant(Map.of()))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void rejects_malformed_tenant_claims() {
    assertThatThrownBy(() -> TokenClaims.tenant(List.of("acme")))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.tenant(Map.of("acme", "x")))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.tenant(Map.of("acme", Map.of())))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.tenant(Map.of("acme", Map.of("id", 42))))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.tenant(Map.of("acme", Map.of("id", "not-a-uuid"))))
        .hasMessageContaining("not a UUID");
  }

  @Test
  void keeps_only_known_roles() {
    Object realmAccess =
        Map.of("roles", List.of("tenant-admin", "offline_access", "default-roles-meterbook", 7));

    assertThat(TokenClaims.roles(realmAccess)).containsExactly(Role.TENANT_ADMIN);
  }

  @Test
  void no_or_malformed_role_claims_mean_no_roles() {
    assertThat(TokenClaims.roles(null)).isEmpty();
    assertThat(TokenClaims.roles(Map.of("roles", "platform-admin"))).isEmpty();
    assertThat(TokenClaims.roles(List.of("platform-admin"))).isEmpty();
  }

  @Test
  void maps_our_identity_provider_aliases_to_connection_ids() {
    assertThat(TokenClaims.ssoConnection("sso-" + ID)).contains(ID);
    assertThat(TokenClaims.ssoConnection("sso-nope")).isEmpty();
    assertThat(TokenClaims.ssoConnection("google")).isEmpty();
    assertThat(TokenClaims.ssoConnection(null)).isEmpty();
  }

  @Test
  void the_subject_must_be_a_user_id() {
    assertThat(TokenClaims.subject(ID.toString())).isEqualTo(ID);
    assertThatThrownBy(() -> TokenClaims.subject("service-account"))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThatThrownBy(() -> TokenClaims.subject(null))
        .isInstanceOf(InvalidBearerTokenException.class);
  }
}
