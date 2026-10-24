package com.meterbook.identity.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test fixture: sets up organizations, users and clients through Keycloak's admin API (as an
 * operator would), and fetches real tokens.
 */
public final class KeycloakAdmin {

  private static final String REALM = "/admin/realms/meterbook";

  private final KeycloakContainer keycloak;
  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  public KeycloakAdmin(KeycloakContainer keycloak) {
    this.keycloak = keycloak;
  }

  public UUID createOrganization(String alias, String domain) {
    send(
        "POST",
        REALM + "/organizations",
        Map.of("name", alias, "alias", alias, "domains", List.of(Map.of("name", domain))));
    return UUID.fromString(
        get(REALM + "/organizations?exact=true&search=" + alias).get(0).get("id").asString());
  }

  public UUID createUser(String email, String firstName, String lastName, String password) {
    send(
        "POST",
        REALM + "/users",
        Map.of(
            "username", email,
            "email", email,
            "emailVerified", true,
            "enabled", true,
            "firstName", firstName,
            "lastName", lastName,
            "credentials",
                List.of(Map.of("type", "password", "value", password, "temporary", false))));
    return UUID.fromString(
        get(REALM + "/users?exact=true&username=" + encode(email)).get(0).get("id").asString());
  }

  public void addMember(UUID organization, UUID user) {
    send("POST", REALM + "/organizations/" + organization + "/members", user.toString());
  }

  public void grantRealmRole(UUID user, String role) {
    JsonNode representation = get(REALM + "/roles/" + role);
    send("POST", REALM + "/users/" + user + "/role-mappings/realm", List.of(representation));
  }

  public String findUserId(String email) {
    return get(REALM + "/users?exact=true&username=" + encode(email)).get(0).get("id").asString();
  }

  public JsonNode identityProvider(String alias) {
    return get(REALM + "/identity-provider/instances/" + alias);
  }

  public List<String> identityProvidersOf(UUID organization) {
    return get(REALM + "/organizations/" + organization + "/identity-providers")
        .valueStream()
        .map(p -> p.get("alias").asString())
        .toList();
  }

  public boolean identityProviderExists(String alias) {
    return get(REALM + "/identity-provider/instances")
        .valueStream()
        .anyMatch(p -> p.get("alias").asString().equals(alias));
  }

  public JsonNode user(UUID user) {
    return get(REALM + "/users/" + user);
  }

  /** Links of the user, as identity provider alias to the subject at that provider. */
  public Map<String, String> federatedIdentities(UUID user) {
    return get(REALM + "/users/" + user + "/federated-identity")
        .valueStream()
        .collect(
            Collectors.toMap(
                f -> f.get("identityProvider").asString(), f -> f.get("userId").asString()));
  }

  /** Requests a token pair; returns the raw response status and body. */
  public HttpResponse<String> tokenResponse(Map<String, String> form) {
    String body =
        form.entrySet().stream()
            .map(e -> e.getKey() + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(keycloak.issuer() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A plain realm, for example a customer's identity provider. */
  public void createRealm(String realm) {
    send("POST", "/admin/realms", Map.of("realm", realm, "enabled", true));
  }

  public void createRealmUser(String realm, String email, String password) {
    send(
        "POST",
        "/admin/realms/" + realm + "/users",
        Map.of(
            "username",
            email,
            "email",
            email,
            "emailVerified",
            true,
            "enabled",
            true,
            "firstName",
            "Test",
            "lastName",
            "User",
            "credentials",
            List.of(Map.of("type", "password", "value", password, "temporary", false))));
  }

  /** The realm's RS256 signing certificate (base64 DER), which signs its SAML assertions. */
  public String signingCertificate(String realm) {
    return get("/admin/realms/" + realm + "/keys")
        .get("keys")
        .valueStream()
        .filter(k -> "RS256".equals(k.path("algorithm").asString()) && k.has("certificate"))
        .findFirst()
        .orElseThrow()
        .get("certificate")
        .asString();
  }

  /** Makes the realm a SAML IdP for one service provider: signed assertions, email as NameID. */
  public void createSamlClient(String realm, String serviceProviderEntityId, String acsUrl) {
    send(
        "POST",
        "/admin/realms/" + realm + "/clients",
        Map.of(
            "clientId",
            serviceProviderEntityId,
            "protocol",
            "saml",
            "enabled",
            true,
            "redirectUris",
            List.of(acsUrl),
            "frontchannelLogout",
            false,
            "attributes",
            Map.of(
                "saml_assertion_consumer_url_post", acsUrl,
                "saml.assertion.signature", "true",
                "saml.server.signature", "true",
                "saml.client.signature", "false",
                "saml.signature.algorithm", "RSA_SHA256",
                "saml_name_id_format", "email",
                "saml.force.name.id.format", "true")));
  }

  public List<JsonNode> domainsOf(UUID organization) {
    return get(REALM + "/organizations/" + organization).get("domains").valueStream().toList();
  }

  public void setPassword(UUID user, String password) {
    send(
        "PUT",
        REALM + "/users/" + user + "/reset-password",
        Map.of("type", "password", "value", password, "temporary", false));
  }

  public List<String> realmRolesOf(UUID user) {
    return get(REALM + "/users/" + user + "/role-mappings/realm")
        .valueStream()
        .map(r -> r.get("name").asString())
        .toList();
  }

  public boolean organizationExists(UUID organization) {
    return get(REALM + "/organizations?first=0&max=1000")
        .valueStream()
        .anyMatch(o -> o.get("id").asString().equals(organization.toString()));
  }

  public boolean userExists(UUID user) {
    return get(REALM + "/users?first=0&max=1000")
        .valueStream()
        .anyMatch(u -> u.get("id").asString().equals(user.toString()));
  }

  /** A test-only client that allows the password grant, with the given default scopes. */
  public void createPasswordClient(String clientId, List<String> scopes) {
    send(
        "POST",
        REALM + "/clients",
        Map.of(
            "clientId", clientId,
            "publicClient", true,
            "directAccessGrantsEnabled", true,
            "standardFlowEnabled", false,
            "defaultClientScopes", scopes));
  }

  public String passwordToken(String clientId, String username, String password) {
    return token(
        Map.of(
            "grant_type",
            "password",
            "client_id",
            clientId,
            "username",
            username,
            "password",
            password));
  }

  public String clientCredentialsToken(String clientId, String secret) {
    return token(
        Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
  }

  private String token(Map<String, String> form) {
    String body =
        form.entrySet().stream()
            .map(e -> e.getKey() + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(keycloak.issuer() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return json.readTree(call(request, 200)).get("access_token").asString();
  }

  private String adminToken() {
    String body =
        "grant_type=password&client_id=admin-cli&username=admin&password="
            + encode(KeycloakContainer.ADMIN_PASSWORD);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(keycloak.baseUrl() + "/realms/master/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return json.readTree(call(request, 200)).get("access_token").asString();
  }

  private JsonNode get(String path) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(keycloak.baseUrl() + path))
            .header("Authorization", "Bearer " + adminToken())
            .GET()
            .build();
    return json.readTree(call(request, 200));
  }

  private void send(String method, String path, Object body) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(keycloak.baseUrl() + path))
            .header("Authorization", "Bearer " + adminToken())
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    call(request, -1);
  }

  private String call(HttpRequest request, int expected) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      boolean ok =
          expected < 0 ? response.statusCode() / 100 == 2 : response.statusCode() == expected;
      if (!ok) {
        throw new IllegalStateException(
            request.method()
                + " "
                + request.uri()
                + " -> "
                + response.statusCode()
                + ": "
                + response.body());
      }
      return response.body();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
