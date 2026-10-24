package com.meterbook.identity.directory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * {@link Directory} on Keycloak's admin REST API, authenticated as the {@code meterbook-identity}
 * service account. Every call has a timeout; reads and deletes are retried twice with jittered
 * backoff, creates are not (a retried create could duplicate).
 */
public final class KeycloakDirectory implements Directory {

  private static final int ATTEMPTS = 3;
  private static final java.util.Set<String> GRANTABLE_ROLES = java.util.Set.of("tenant-admin");
  private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

  private final RestClient http;
  private final KeycloakProperties properties;
  private final Clock clock;
  private final String admin;

  private volatile String token;
  private volatile Instant tokenExpiry = Instant.EPOCH;

  public KeycloakDirectory(RestClient.Builder builder, KeycloakProperties properties, Clock clock) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(properties.timeout());
    this.http = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
    this.clock = clock;
    this.admin = "/admin/realms/" + properties.realm();
  }

  @Override
  public UUID createOrganization(String name, String alias, List<String> domains) {
    URI location =
        call(
            () ->
                http.post()
                    .uri(admin + "/organizations")
                    .headers(this::bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "name", name,
                            "alias", alias,
                            "domains", domains.stream().map(d -> Map.of("name", d)).toList()))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation());
    return idFrom(location);
  }

  @Override
  public void deleteOrganization(UUID organization) {
    retried(() -> delete(admin + "/organizations/" + organization));
  }

  @Override
  public Optional<UUID> findUserByEmail(String email) {
    JsonNode users =
        retried(
            () ->
                http.get()
                    .uri(
                        b ->
                            b.path(admin + "/users")
                                .queryParam("email", email)
                                .queryParam("exact", true)
                                .build())
                    .headers(this::bearer)
                    .retrieve()
                    .body(JsonNode.class));
    if (users == null || users.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(users.get(0).get("id").asString()));
  }

  @Override
  public UUID createUser(String email, String firstName, String lastName) {
    URI location =
        call(
            () ->
                http.post()
                    .uri(admin + "/users")
                    .headers(this::bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "username", email,
                            "email", email,
                            "firstName", firstName,
                            "lastName", lastName,
                            "enabled", true))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation());
    return idFrom(location);
  }

  @Override
  public void deleteUser(UUID user) {
    retried(() -> delete(admin + "/users/" + user));
  }

  @Override
  public List<UUID> organizationsOf(UUID user) {
    JsonNode organizations =
        retried(
            () ->
                http.get()
                    .uri(admin + "/organizations/members/" + user + "/organizations")
                    .headers(this::bearer)
                    .retrieve()
                    .body(JsonNode.class));
    if (organizations == null) {
      return List.of();
    }
    return organizations.valueStream().map(o -> UUID.fromString(o.get("id").asString())).toList();
  }

  @Override
  public void addMember(UUID organization, UUID user) {
    // Adding an existing member is a 409 that changes nothing, so this is safe to retry.
    retried(
        () -> {
          try {
            http.post()
                .uri(admin + "/organizations/" + organization + "/members")
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(user.toString())
                .retrieve()
                .toBodilessEntity();
          } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
              throw e;
            }
          }
          return null;
        });
  }

  @Override
  public void grantRealmRole(UUID user, String role) {
    // The service account may map any realm role (manage-users), so which roles it grants is
    // limited here.
    if (!GRANTABLE_ROLES.contains(role)) {
      throw new IllegalArgumentException("the identity service does not grant " + role);
    }
    retried(
        () -> {
          // Reading a role by name needs view-realm; the roles still available to this user need
          // only
          // manage-users and carry the representation the mapping call wants.
          JsonNode available =
              http.get()
                  .uri(admin + "/users/" + user + "/role-mappings/realm/available")
                  .headers(this::bearer)
                  .retrieve()
                  .body(JsonNode.class);
          List<JsonNode> matching =
              available == null
                  ? List.of()
                  : available
                      .valueStream()
                      .filter(r -> role.equals(r.get("name").asString()))
                      .toList();
          if (matching.isEmpty()) {
            return null; // already granted
          }
          http.post()
              .uri(admin + "/users/" + user + "/role-mappings/realm")
              .headers(this::bearer)
              .contentType(MediaType.APPLICATION_JSON)
              .body(matching)
              .retrieve()
              .toBodilessEntity();
          return null;
        });
  }

  @Override
  public void updateUser(UUID user, String email, String firstName, String lastName) {
    retried(
        () ->
            http.put()
                .uri(admin + "/users/" + user)
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                // The realm keeps the username equal to the email (registrationEmailAsUsername).
                .body(Map.of("email", email, "firstName", firstName, "lastName", lastName))
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public void setEnabled(UUID user, boolean enabled) {
    retried(
        () ->
            http.put()
                .uri(admin + "/users/" + user)
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled))
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public void logout(UUID user) {
    retried(
        () ->
            http.post()
                .uri(admin + "/users/" + user + "/logout")
                .headers(this::bearer)
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public void removeMember(UUID organization, UUID user) {
    retried(() -> delete(admin + "/organizations/" + organization + "/members/" + user));
  }

  @Override
  public void linkIdentity(UUID user, String providerAlias, String subject) {
    String path = admin + "/users/" + user + "/federated-identity/" + providerAlias;
    retried(
        () -> {
          delete(path); // a user has at most one link per provider; replace it
          http.post()
              .uri(path)
              .headers(this::bearer)
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of("identityProvider", providerAlias, "userId", subject, "userName", subject))
              .retrieve()
              .toBodilessEntity();
          return null;
        });
  }

  /**
   * The realm's first-sign-in flow: create the user if the email is unused, never link by email.
   */
  static final String FIRST_BROKER_LOGIN_FLOW = "meterbook first broker login";

  @Override
  public void createSamlProvider(UUID organization, SamlProvider provider) {
    Map<String, String> config =
        Map.ofEntries(
            Map.entry("entityId", provider.serviceProviderEntityId()),
            Map.entry("idpEntityId", provider.idpEntityId()),
            Map.entry("singleSignOnServiceUrl", provider.ssoUrl()),
            Map.entry("signingCertificate", provider.signingCertificate()),
            Map.entry("validateSignature", "true"),
            Map.entry("wantAssertionsSigned", "true"),
            Map.entry("wantAuthnRequestsSigned", "true"),
            Map.entry("postBindingResponse", "true"),
            Map.entry("postBindingAuthnRequest", "true"),
            Map.entry(
                "nameIDPolicyFormat", "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"),
            Map.entry("principalType", "SUBJECT"),
            Map.entry("syncMode", "FORCE"),
            Map.entry("allowedClockSkew", "30"),
            // Route sign-ins for any of the organization's domains to this IdP.
            Map.entry("kc.org.domain", "ANY"),
            Map.entry("kc.org.broker.redirect.mode.email-matches", "true"));
    call(
        () ->
            http.post()
                .uri(admin + "/identity-provider/instances")
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    Map.of(
                        "alias",
                        provider.alias(),
                        "displayName",
                        provider.displayName(),
                        "providerId",
                        "saml",
                        "enabled",
                        true,
                        // The IdP proves the email; the domain belongs to this tenant only.
                        "trustEmail",
                        true,
                        "hideOnLogin",
                        true,
                        "firstBrokerLoginFlowAlias",
                        FIRST_BROKER_LOGIN_FLOW,
                        "config",
                        config))
                .retrieve()
                .toBodilessEntity());
    // Linking twice is a 409 that changes nothing, so this is safe to retry.
    retried(
        () -> {
          try {
            http.post()
                .uri(admin + "/organizations/" + organization + "/identity-providers")
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(provider.alias())
                .retrieve()
                .toBodilessEntity();
          } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
              throw e;
            }
          }
          return null;
        });
  }

  @Override
  public void deleteIdentityProvider(String alias) {
    retried(() -> delete(admin + "/identity-provider/instances/" + alias));
  }

  private Void delete(String path) {
    try {
      http.delete().uri(path).headers(this::bearer).retrieve().toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != 404) {
        throw e;
      }
    }
    return null;
  }

  private void bearer(HttpHeaders headers) {
    headers.setBearerAuth(accessToken());
  }

  private String accessToken() {
    if (token == null || Instant.now(clock).isAfter(tokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
      synchronized (this) {
        if (token == null || Instant.now(clock).isAfter(tokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
          LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
          form.add("grant_type", "client_credentials");
          form.add("client_id", properties.clientId());
          form.add("client_secret", properties.clientSecret());
          JsonNode response =
              call(
                  () ->
                      http.post()
                          .uri("/realms/" + properties.realm() + "/protocol/openid-connect/token")
                          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                          .body(form)
                          .retrieve()
                          .body(JsonNode.class));
          token = response.get("access_token").asString();
          tokenExpiry = Instant.now(clock).plusSeconds(response.get("expires_in").asLong());
        }
      }
    }
    return token;
  }

  private <T> T retried(Supplier<T> action) {
    for (int attempt = 1; ; attempt++) {
      try {
        return call(action);
      } catch (UnavailableException e) {
        if (attempt == ATTEMPTS) {
          throw e;
        }
        sleep(
            Duration.ofMillis(100L * (1L << attempt) + ThreadLocalRandom.current().nextLong(100)));
      }
    }
  }

  /** Runs one call and maps transport and HTTP failures to the {@link Directory} exceptions. */
  private static <T> T call(Supplier<T> action) {
    try {
      return action.get();
    } catch (ResourceAccessException e) {
      throw new UnavailableException("Keycloak did not answer in time", e);
    } catch (RestClientResponseException e) {
      HttpStatusCode status = e.getStatusCode();
      if (status.is5xxServerError()) {
        throw new UnavailableException("Keycloak failed with " + status.value(), e);
      }
      if (status.value() == 409 || isDomainConflict(e)) {
        throw new ConflictException(errorMessage(e));
      }
      throw new IllegalStateException(
          "Keycloak rejected a request with " + status.value() + ": " + errorMessage(e), e);
    }
  }

  // Keycloak reports a domain already linked to another organization as 400, not 409.
  private static boolean isDomainConflict(RestClientResponseException e) {
    return e.getStatusCode().value() == 400 && errorMessage(e).contains("already linked");
  }

  private static String errorMessage(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    return body.length() > 300 ? body.substring(0, 300) : body;
  }

  private static UUID idFrom(URI location) {
    if (location == null) {
      throw new IllegalStateException("Keycloak created a resource without a Location");
    }
    String path = location.getPath();
    return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("interrupted while waiting to retry", e);
    }
  }
}
