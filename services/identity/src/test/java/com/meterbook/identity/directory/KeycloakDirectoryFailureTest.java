package com.meterbook.identity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** How the adapter reacts when Keycloak misbehaves, played by a local HTTP stub. */
class KeycloakDirectoryFailureTest {

  /** One canned response. */
  record Reply(int status, String body, Map<String, String> headers) {
    static Reply of(int status, String body) {
      return new Reply(status, body, Map.of());
    }
  }

  private HttpServer server;
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
  private KeycloakDirectory directory;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    replies.put(
        "POST /realms/meterbook/protocol/openid-connect/token",
        Reply.of(200, "{\"access_token\":\"t\",\"expires_in\":300}"));
    directory =
        new KeycloakDirectory(
            RestClient.builder(),
            new KeycloakProperties(
                "http://localhost:" + server.getAddress().getPort(),
                "meterbook",
                "meterbook-identity",
                "s",
                Duration.ofSeconds(1)),
            Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
    hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    Reply reply = replies.getOrDefault(key, Reply.of(404, "{}"));
    reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      exchange.getResponseBody().write(body);
    }
    exchange.close();
  }

  private int hits(String key) {
    AtomicInteger n = hits.get(key);
    return n == null ? 0 : n.get();
  }

  @Test
  void server_errors_are_retried_then_reported_as_unavailable() {
    replies.put("GET /admin/realms/meterbook/users", Reply.of(503, "{}"));

    assertThatThrownBy(() -> directory.findUserByEmail("a@b.test"))
        .isInstanceOf(Directory.UnavailableException.class);
    assertThat(hits("GET /admin/realms/meterbook/users")).isEqualTo(3);
  }

  @Test
  void creates_are_not_retried() {
    replies.put("POST /admin/realms/meterbook/organizations", Reply.of(502, "{}"));

    assertThatThrownBy(() -> directory.createOrganization("a", "a", List.of("a.test")))
        .isInstanceOf(Directory.UnavailableException.class);
    assertThat(hits("POST /admin/realms/meterbook/organizations")).isEqualTo(1);
  }

  @Test
  void a_create_without_a_location_is_an_error() {
    replies.put("POST /admin/realms/meterbook/users", Reply.of(201, ""));

    assertThatThrownBy(() -> directory.createUser("a@b.test", "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Location");
  }

  @Test
  void a_rejected_request_that_is_not_a_conflict_is_a_bug_and_its_message_is_truncated() {
    replies.put(
        "POST /admin/realms/meterbook/organizations",
        Reply.of(400, "{\"errorMessage\":\"" + "x".repeat(1000) + "\"}"));

    assertThatThrownBy(() -> directory.createOrganization("a", "a", List.of("a.test")))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
  }

  @Test
  void missing_lists_are_empty() {
    UUID user = UUID.randomUUID();
    replies.put("GET /admin/realms/meterbook/users", Reply.of(200, "[]"));
    replies.put(
        "GET /admin/realms/meterbook/organizations/members/" + user + "/organizations",
        Reply.of(200, ""));
    replies.put(
        "GET /admin/realms/meterbook/users/" + user + "/role-mappings/realm/available",
        Reply.of(200, "[]"));

    assertThat(directory.findUserByEmail("a@b.test")).isEmpty();
    assertThat(directory.organizationsOf(user)).isEmpty();
    directory.grantRealmRole(
        user, "tenant-admin"); // nothing available: already granted, no mapping call
    assertThat(hits("POST /admin/realms/meterbook/users/" + user + "/role-mappings/realm"))
        .isZero();
  }

  @Test
  void deletes_and_links_of_something_already_gone_or_present_succeed() {
    UUID organization = UUID.randomUUID();
    replies.put(
        "DELETE /admin/realms/meterbook/organizations/" + organization, Reply.of(404, "{}"));
    replies.put(
        "POST /admin/realms/meterbook/organizations/" + organization + "/members",
        Reply.of(409, "{}"));

    directory.deleteOrganization(organization);
    directory.addMember(organization, UUID.randomUUID());
  }

  @Test
  void other_failures_of_retried_calls_are_not_swallowed() {
    UUID organization = UUID.randomUUID();
    replies.put(
        "DELETE /admin/realms/meterbook/organizations/" + organization, Reply.of(403, "{}"));
    replies.put(
        "POST /admin/realms/meterbook/organizations/" + organization + "/members",
        Reply.of(403, "{}"));
    replies.put("POST /admin/realms/meterbook/identity-provider/instances", Reply.of(201, ""));
    replies.put(
        "POST /admin/realms/meterbook/organizations/" + organization + "/identity-providers",
        Reply.of(403, "{}"));

    assertThatThrownBy(() -> directory.deleteOrganization(organization))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> directory.addMember(organization, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                directory.createSamlProvider(
                    organization,
                    new Directory.SamlProvider("sso-x", "X", "sp", "idp", "https://idp", "cert")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void the_token_is_fetched_once_and_reused() {
    replies.put("GET /admin/realms/meterbook/users", Reply.of(200, "[]"));

    directory.findUserByEmail("a@b.test");
    directory.findUserByEmail("c@d.test");

    assertThat(hits("POST /realms/meterbook/protocol/openid-connect/token")).isEqualTo(1);
  }
}
