package com.meterbook.identity.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.support.KeycloakAdmin;
import com.meterbook.identity.support.KeycloakContainer;
import com.meterbook.identity.support.TestDatabase;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TC-PLT-001, the MB-T07 Definition of Done: a real browser signs in to Meterbook through a
 * tenant's SAML identity provider. Everything is set up through Meterbook's own APIs, as an
 * operator, a tenant admin and the tenant's SCIM client would; the customer IdP is a second
 * Keycloak realm.
 */
@Tag("e2e")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsoEndToEndTest {

  @Container static final KeycloakContainer KEYCLOAK = new KeycloakContainer();

  private static final String PORTAL = "http://localhost:3000";
  private static final String PASSWORD = "a-long-test-password";

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KEYCLOAK::issuer);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", KEYCLOAK::jwksUri);
    registry.add("identity.keycloak.base-url", KEYCLOAK::baseUrl);
    registry.add("identity.keycloak.client-secret", () -> KeycloakContainer.IDENTITY_CLIENT_SECRET);
    registry.add(
        "identity.sso.allow-insecure-idp-urls", () -> "true"); // the test IdP is plain http
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;

  private static Playwright playwright;
  private static Browser browser;
  private static KeycloakAdmin admin;

  // Shared across the ordered steps.
  private static String acmeTenant;
  private static String acmeConnection;
  private static String scimToken;
  private static String janeScimId;
  private static String janeRefreshToken;

  @BeforeAll
  static void start() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    admin = new KeycloakAdmin(KEYCLOAK);
  }

  @AfterAll
  static void stop() {
    browser.close();
    playwright.close();
  }

  // --- set-up through Meterbook's APIs
  // -------------------------------------------------------------

  private JsonNode call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expected)
      throws Exception {
    var response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
    String body = response.getContentAsString();
    return body.isEmpty() ? null : json.readTree(body);
  }

  private String createTenant(String slug, String domain, String adminEmail) throws Exception {
    String operator =
        admin.clientCredentialsToken(
            "meterbook-operator", KeycloakContainer.OPERATOR_CLIENT_SECRET);
    JsonNode tenant =
        call(
            post("/v1/tenants")
                .header("Authorization", "Bearer " + operator)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            slug,
                            "slug",
                            slug,
                            "domains",
                            List.of(domain),
                            "initial_admin_email",
                            adminEmail))),
            201);
    // Test fixture only: the first admin sets a password (in production: SSO or a reset email).
    admin.setPassword(UUID.fromString(admin.findUserId(adminEmail)), PASSWORD);
    return tenant.get("id").asString();
  }

  private String tenantAdminToken(String email) {
    return admin.passwordToken("test-admin-console", email, PASSWORD);
  }

  /** Connects a tenant to a fresh IdP realm and returns the connection ID. */
  private String connectIdp(String adminToken, String idpRealm) throws Exception {
    admin.createRealm(idpRealm);
    String idpIssuer = KEYCLOAK.baseUrl() + "/realms/" + idpRealm;
    JsonNode connection =
        call(
            post("/v1/sso-connections")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "display_name",
                            idpRealm,
                            "protocol",
                            "saml",
                            "saml",
                            Map.of(
                                "entity_id",
                                idpIssuer,
                                "sso_url",
                                idpIssuer + "/protocol/saml",
                                "signing_certificate",
                                admin.signingCertificate(idpRealm))))),
            201);
    // The tenant admin configures their IdP with the values Meterbook returned.
    JsonNode sp = connection.get("service_provider");
    admin.createSamlClient(idpRealm, sp.get("entity_id").asString(), sp.get("acs_url").asString());
    return connection.get("id").asString();
  }

  // --- the browser
  // -----------------------------------------------------------------------------------

  /** The outcome of one browser sign-in: the code Keycloak sent back, or the page it stopped on. */
  record SignIn(Optional<String> code, String verifier, String lastPageText) {}

  /**
   * Starts the portal's authorization-code + PKCE flow and signs in at whichever IdP Keycloak sends
   * us to.
   */
  private SignIn signIn(String email, String idpHint) throws Exception {
    String verifier =
        Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(32));
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    String url =
        KEYCLOAK.issuer()
            + "/protocol/openid-connect/auth?client_id=meterbook-portal&response_type=code&scope=openid"
            + "&redirect_uri="
            + URLEncoder.encode(PORTAL + "/callback", StandardCharsets.UTF_8)
            + "&code_challenge="
            + challenge
            + "&code_challenge_method=S256&state="
            + UUID.randomUUID()
            + (idpHint == null ? "" : "&kc_idp_hint=" + idpHint);
    AtomicReference<String> code = new AtomicReference<>();
    try (BrowserContext context = browser.newContext()) {
      // No portal runs in the test. Keycloak reaches the callback by redirect, which routes do not
      // intercept, so the code is read from the request itself.
      context.onRequest(
          request -> {
            if (request.url().startsWith(PORTAL)) {
              String query = URI.create(request.url()).getQuery();
              for (String pair : query == null ? new String[0] : query.split("&")) {
                if (pair.startsWith("code=")) {
                  code.set(pair.substring(5));
                }
              }
            }
          });
      Page page = context.newPage();
      page.navigate(url);
      if (idpHint == null) {
        // Meterbook's identity-first page: the email domain decides which IdP signs the user in.
        page.fill("#username", email);
        page.click("#kc-login");
      }
      // The customer's IdP login page (another realm), reached through a SAML redirect, unless
      // Meterbook stops the sign-in first (for example, a disabled account).
      String errors = "#kc-error-message, .kc-feedback-text, #input-error";
      try {
        page.waitForCondition(
            () -> !page.url().contains("/realms/meterbook/") || page.locator(errors).count() > 0,
            new Page.WaitForConditionOptions().setTimeout(20_000));
      } catch (com.microsoft.playwright.TimeoutError neither) {
        // Reported below.
      }
      if (page.url().contains("/realms/meterbook/")) {
        return new SignIn(
            Optional.empty(),
            verifier,
            "not sent to an IdP; stopped at " + page.url() + ": " + page.textContent("body"));
      }
      page.fill("#username", email);
      page.fill("#password", PASSWORD);
      page.click("#kc-login");
      // The SAML response is POSTed back to Meterbook's broker, which finishes the sign-in or stops
      // with an error.
      // Done when the code arrives, or when Meterbook's broker shows an error page instead.
      try {
        page.waitForCondition(
            () ->
                code.get() != null
                    || page.locator("#kc-error-message, .kc-feedback-text, #input-error").count()
                        > 0,
            new Page.WaitForConditionOptions().setTimeout(20_000));
      } catch (com.microsoft.playwright.TimeoutError neither) {
        // Reported through the diagnostics below.
      }
      String diagnostics =
          code.get() != null
              ? ""
              : page.url()
                  + " | "
                  + page.textContent("body")
                  + " | keycloak: "
                  + KEYCLOAK
                      .getLogs()
                      .lines()
                      .filter(l -> l.contains("WARN") || l.contains("ERROR"))
                      .reduce((a, b) -> b + "\n" + a)
                      .orElse("");
      return new SignIn(
          Optional.ofNullable(code.get()), verifier, page.textContent("body") + diagnostics);
    }
  }

  private Map<String, String> exchange(SignIn signIn) throws Exception {
    var response =
        admin.tokenResponse(
            Map.of(
                "grant_type",
                "authorization_code",
                "client_id",
                "meterbook-portal",
                "code",
                signIn.code().orElseThrow(),
                "redirect_uri",
                PORTAL + "/callback",
                "code_verifier",
                signIn.verifier()));
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode tokens = json.readTree(response.body());
    return Map.of(
        "access",
        tokens.get("access_token").asString(),
        "refresh",
        tokens.get("refresh_token").asString());
  }

  // --- the scenario
  // ----------------------------------------------------------------------------------

  @Test
  @Order(1)
  void a_tenant_admin_connects_their_idp_and_provisions_a_user_over_scim() throws Exception {
    admin.createPasswordClient(
        "test-admin-console",
        List.of(
            "basic", "roles", "profile", "email", "meterbook-tenant", "meterbook-api-audience"));
    acmeTenant = createTenant("acme", "acme.test", "ada@acme.test");
    String ada = tenantAdminToken("ada@acme.test");
    acmeConnection = connectIdp(ada, "acme-idp");
    scimToken =
        call(
                post("/v1/scim-tokens")
                    .header("Authorization", "Bearer " + ada)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content("{\"description\":\"acme IdP provisioning\"}"),
                201)
            .get("secret")
            .asString();

    // The customer's IdP has Jane; their SCIM client provisions her into Meterbook.
    admin.createRealmUser("acme-idp", "jane@acme.test", PASSWORD);
    janeScimId =
        call(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer " + scimToken)
                    .contentType("application/scim+json")
                    .content(
                        "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"jane@acme.test\","
                            + "\"name\":{\"givenName\":\"Jane\",\"familyName\":\"Doe\"},\"active\":true}"),
                201)
            .get("id")
            .asString();
  }

  @Test
  @Order(2)
  void the_provisioned_user_signs_in_through_the_tenants_saml_idp() throws Exception {
    SignIn signIn = signIn("jane@acme.test", null);
    assertThat(signIn.code()).as("sign-in stopped at: %s", signIn.lastPageText()).isPresent();
    Map<String, String> tokens = exchange(signIn);
    janeRefreshToken = tokens.get("refresh");

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + tokens.get("access")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(acmeTenant))
        .andExpect(jsonPath("$.email").value("jane@acme.test"))
        .andExpect(jsonPath("$.name").value("Jane Doe"))
        .andExpect(jsonPath("$.login_method").value("sso"))
        .andExpect(jsonPath("$.sso_connection_id").value(acmeConnection));
  }

  @Test
  @Order(3)
  void a_user_the_tenant_did_not_provision_is_created_inside_the_tenant() throws Exception {
    admin.createRealmUser("acme-idp", "bob@acme.test", PASSWORD);

    SignIn signIn = signIn("bob@acme.test", null);
    assertThat(signIn.code()).as("sign-in stopped at: %s", signIn.lastPageText()).isPresent();

    mvc.perform(get("/v1/me").header("Authorization", "Bearer " + exchange(signIn).get("access")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(acmeTenant))
        .andExpect(jsonPath("$.login_method").value("sso"));
  }

  @Test
  @Order(4)
  void another_tenants_idp_cannot_sign_in_as_this_tenants_user() throws Exception {
    // Globex controls its own IdP and makes it assert Jane's email address.
    createTenant("globex", "globex.test", "gus@globex.test");
    String globexConnection = connectIdp(tenantAdminToken("gus@globex.test"), "globex-idp");
    admin.createRealmUser("globex-idp", "jane@acme.test", PASSWORD);

    SignIn attempt = signIn("jane@acme.test", "sso-" + globexConnection);

    assertThat(attempt.code()).as("Globex's IdP must not sign in as Acme's Jane").isEmpty();
    // It got as far as Globex's IdP and back; Meterbook's broker refused to link the existing
    // account.
    assertThat(attempt.lastPageText())
        .doesNotStartWith("not sent to an IdP")
        .contains("already exists");
  }

  @Test
  @Order(5)
  void deactivating_the_user_over_scim_ends_their_session_and_blocks_sign_in() throws Exception {
    call(
        patch("/scim/v2/Users/" + janeScimId)
            .header("Authorization", "Bearer " + scimToken)
            .contentType("application/scim+json")
            .content(
                "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                    + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}"),
        200);

    var refreshed =
        admin.tokenResponse(
            Map.of(
                "grant_type",
                "refresh_token",
                "client_id",
                "meterbook-portal",
                "refresh_token",
                janeRefreshToken));
    assertThat(refreshed.statusCode()).as("the existing session must be gone").isEqualTo(400);

    SignIn again = signIn("jane@acme.test", null);
    assertThat(again.code()).as("a deactivated user must not sign in").isEmpty();
    assertThat(again.lastPageText().toLowerCase(java.util.Locale.ROOT)).contains("disabled");
  }

  @Test
  @Order(6)
  void an_api_key_from_the_tenant_admin_works_until_it_is_revoked() throws Exception {
    // MB-T09 Definition of Done (TC-PLT-003): a revoked key is rejected within 60 s.
    String ada = tenantAdminToken("ada@acme.test");
    JsonNode project =
        call(
            post("/v1/projects")
                .header("Authorization", "Bearer " + ada)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"name\":\"Checkout\",\"slug\":\"checkout\"}"),
            201);
    String keys =
        "/v1/projects/"
            + project.get("id").asString()
            + "/environments/"
            + project.get("environments").get(0).get("id").asString()
            + "/api-keys";
    JsonNode key =
        call(
            post(keys)
                .header("Authorization", "Bearer " + ada)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"name\":\"Usage ingest\",\"scopes\":[\"usage:write\"]}"),
            201);
    String secret = key.get("secret").asString();
    assertThat(secret).startsWith("mb_live_");

    mvc.perform(get("/v1/api-key").header("Authorization", "Bearer " + secret))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(acmeTenant))
        .andExpect(jsonPath("$.kind").value("live"));
    // A key is not a person: it cannot call the admin API.
    mvc.perform(get("/v1/projects").header("Authorization", "Bearer " + secret))
        .andExpect(status().isUnauthorized());

    call(
        delete(keys + "/" + key.get("id").asString()).header("Authorization", "Bearer " + ada),
        204);
    long revoked = System.nanoTime();
    mvc.perform(get("/v1/api-key").header("Authorization", "Bearer " + secret))
        .andExpect(status().isUnauthorized());
    assertThat(Duration.ofNanos(System.nanoTime() - revoked)).isLessThan(Duration.ofSeconds(60));
  }
}
