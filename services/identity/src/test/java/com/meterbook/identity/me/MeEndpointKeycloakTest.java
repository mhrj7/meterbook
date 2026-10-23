package com.meterbook.identity.me;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.support.KeycloakAdmin;
import com.meterbook.identity.support.KeycloakContainer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code GET /v1/me} with real tokens from Keycloak running Meterbook's realm. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MeEndpointKeycloakTest {

  @Container static final KeycloakContainer KEYCLOAK = new KeycloakContainer();

  private static final String PASSWORD = "a-long-test-password";
  private static final List<String> PORTAL_SCOPES =
      List.of("basic", "roles", "profile", "email", "meterbook-tenant", "meterbook-api-audience");

  private static KeycloakAdmin admin;
  private static UUID acme;
  private static UUID globex;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KEYCLOAK::issuer);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", KEYCLOAK::jwksUri);
  }

  @BeforeAll
  static void tenants() {
    admin = new KeycloakAdmin(KEYCLOAK);
    acme = admin.createOrganization("acme", "acme.test");
    globex = admin.createOrganization("globex", "globex.test");
    admin.createPasswordClient("test-portal", PORTAL_SCOPES);
    admin.createPasswordClient(
        "test-no-audience", List.of("basic", "roles", "profile", "email", "meterbook-tenant"));
  }

  private static String userToken(String email, UUID... organizations) {
    UUID user = admin.createUser(email, "Jane", "Doe", PASSWORD);
    for (UUID organization : organizations) {
      admin.addMember(organization, user);
    }
    return admin.passwordToken("test-portal", email, PASSWORD);
  }

  @Test
  void describes_a_tenant_user() throws Exception {
    mvc.perform(
            get("/v1/me").header("Authorization", "Bearer " + userToken("jane@acme.test", acme)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(acme.toString()))
        .andExpect(jsonPath("$.email").value("jane@acme.test"))
        .andExpect(jsonPath("$.name").value("Jane Doe"))
        .andExpect(jsonPath("$.roles").isEmpty())
        .andExpect(jsonPath("$.login_method").value("password"))
        .andExpect(jsonPath("$.user_id").isString())
        .andExpect(jsonPath("$.sso_connection_id").doesNotExist());
  }

  @Test
  void shows_the_tenant_admin_role() throws Exception {
    UUID user = admin.createUser("admin@acme.test", "Ada", "Admin", PASSWORD);
    admin.addMember(acme, user);
    admin.grantRealmRole(user, "tenant-admin");
    String token = admin.passwordToken("test-portal", "admin@acme.test", PASSWORD);

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(user.toString()))
        .andExpect(jsonPath("$.roles[0]").value("tenant-admin"));
  }

  @Test
  void rejects_a_user_of_two_tenants() throws Exception {
    mvc.perform(
            get("/v1/me")
                .header("Authorization", "Bearer " + userToken("both@acme.test", acme, globex)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.type").value("authentication_error"));
  }

  @Test
  void rejects_a_user_of_no_tenant() throws Exception {
    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + userToken("nobody@example.test")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_platform_operator_has_no_tenant_to_describe() throws Exception {
    String token =
        admin.clientCredentialsToken(
            "meterbook-operator", KeycloakContainer.OPERATOR_CLIENT_SECRET);

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("tenant_required"));
  }

  @Test
  void rejects_a_token_not_meant_for_meterbook_apis() throws Exception {
    UUID user = admin.createUser("aud@acme.test", "A", "U", PASSWORD);
    admin.addMember(acme, user);
    String token = admin.passwordToken("test-no-audience", "aud@acme.test", PASSWORD);

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @SuppressWarnings("JavaUtilDate") // Nimbus's claims builder takes java.util.Date
  void rejects_a_token_signed_by_anyone_but_keycloak() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("forged").generate();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(KEYCLOAK.issuer())
            .audience("meterbook-api")
            .subject(UUID.randomUUID().toString())
            .claim("organization", Map.of("acme", Map.of("id", acme.toString())))
            .claim("realm_access", Map.of("roles", List.of("platform-admin")))
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
    var jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("forged").build(), claims);
    jwt.sign(new RSASSASigner(key));

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + jwt.serialize()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejects_garbage_with_a_bearer_challenge() throws Exception {
    mvc.perform(get("/v1/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"meterbook\""));
  }
}
