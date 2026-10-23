package com.meterbook.identity.tenants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.support.KeycloakAdmin;
import com.meterbook.identity.support.KeycloakContainer;
import com.meterbook.identity.support.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator creates a tenant through the API; its first admin then signs in as that tenant's admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@Testcontainers
class TenantKeycloakTest {

  @Container static final KeycloakContainer KEYCLOAK = new KeycloakContainer();

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KEYCLOAK::issuer);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", KEYCLOAK::jwksUri);
    registry.add("identity.keycloak.base-url", KEYCLOAK::baseUrl);
    registry.add("identity.keycloak.client-secret", () -> KeycloakContainer.IDENTITY_CLIENT_SECRET);
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;

  @Test
  void the_first_admin_of_a_new_tenant_signs_in_as_its_admin() throws Exception {
    KeycloakAdmin admin = new KeycloakAdmin(KEYCLOAK);
    String operator =
        admin.clientCredentialsToken(
            "meterbook-operator", KeycloakContainer.OPERATOR_CLIENT_SECRET);

    String created =
        mvc.perform(
                post("/v1/tenants")
                    .header("Authorization", "Bearer " + operator)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(
                        """
                        {"name":"Initech","slug":"initech","domains":["initech.test"],"initial_admin_email":"bill@initech.test"}"""))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String tenant = json.readTree(created).get("id").asString();

    // Test fixture only: give the admin a password (in production they sign in through SSO or a
    // reset email).
    UUID bill = UUID.fromString(admin.findUserId("bill@initech.test"));
    admin.setPassword(bill, "a-long-test-password");
    admin.createPasswordClient(
        "test-portal",
        List.of(
            "basic", "roles", "profile", "email", "meterbook-tenant", "meterbook-api-audience"));
    String token = admin.passwordToken("test-portal", "bill@initech.test", "a-long-test-password");

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(tenant))
        .andExpect(jsonPath("$.email").value("bill@initech.test"))
        .andExpect(jsonPath("$.roles[0]").value("tenant-admin"));
    mvc.perform(get("/v1/tenants/" + tenant).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.domains[0]").value("initech.test"));
  }
}
