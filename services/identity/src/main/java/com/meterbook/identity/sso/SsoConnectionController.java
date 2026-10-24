package com.meterbook.identity.sso;

import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/sso-connections} (contracts/openapi/identity-v1.yaml). */
@RestController
public class SsoConnectionController {

  /** Request body of {@code POST /v1/sso-connections}. */
  public record CreateSsoConnection(
      @NotBlank @Size(max = 100) String displayName,
      @NotNull @Pattern(regexp = "saml") String protocol,
      @NotNull @Valid Saml saml) {

    /** The tenant's SAML IdP. */
    public record Saml(
        @NotBlank @Size(max = 1024) String entityId,
        @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://.+") String ssoUrl,
        @NotBlank @Size(min = 100, max = 16384) String signingCertificate) {}
  }

  /** Response body. */
  public record SsoConnection(
      UUID id,
      String displayName,
      String protocol,
      SamlView saml,
      ServiceProviderView serviceProvider,
      OffsetDateTime createdAt) {

    /** The IdP side. */
    public record SamlView(String entityId, String ssoUrl) {}

    /** What to configure in the IdP. */
    public record ServiceProviderView(String entityId, String acsUrl, String metadataUrl) {}

    static SsoConnection of(SsoConnectionService.Connection c) {
      var s = c.stored();
      var sp = c.serviceProvider();
      return new SsoConnection(
          s.id(),
          s.displayName(),
          "saml",
          new SamlView(s.idpEntityId(), s.ssoUrl()),
          new ServiceProviderView(sp.entityId(), sp.acsUrl(), sp.metadataUrl()),
          s.createdAt());
    }
  }

  /** A page. */
  public record SsoConnectionList(List<SsoConnection> data, boolean hasMore, String nextCursor) {}

  private final SsoConnectionService connections;
  private final Idempotency idempotency;

  public SsoConnectionController(SsoConnectionService connections, Idempotency idempotency) {
    this.connections = connections;
    this.idempotency = idempotency;
  }

  @PostMapping("/v1/sso-connections")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateSsoConnection body) {
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/sso-connections",
        body,
        () -> {
          var created =
              connections.create(
                  auth.caller(),
                  new SsoConnectionService.NewSamlConnection(
                      body.displayName(),
                      body.saml().entityId(),
                      body.saml().ssoUrl(),
                      body.saml().signingCertificate()));
          return ResponseEntity.created(URI.create("/v1/sso-connections/" + created.stored().id()))
              .body(SsoConnection.of(created));
        });
  }

  @GetMapping("/v1/sso-connections")
  public SsoConnectionList list(
      CallerAuthentication auth,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "starting_after", required = false) String startingAfter) {
    if (limit < 1 || limit > 200) {
      throw ApiException.invalid("limit_out_of_range", "limit must be between 1 and 200", "limit");
    }
    Optional<UUID> cursor;
    try {
      cursor = Optional.ofNullable(startingAfter).map(UUID::fromString);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid(
          "cursor_invalid", "starting_after is not a cursor from this API", "starting_after");
    }
    var page = connections.list(auth.caller(), cursor, limit);
    return new SsoConnectionList(
        page.data().stream().map(SsoConnection::of).toList(), page.hasMore(), page.nextCursor());
  }

  @GetMapping("/v1/sso-connections/{id}")
  public SsoConnection get(CallerAuthentication auth, @PathVariable UUID id) {
    return SsoConnection.of(connections.get(auth.caller(), id));
  }

  @DeleteMapping("/v1/sso-connections/{id}")
  public ResponseEntity<Void> delete(CallerAuthentication auth, @PathVariable UUID id) {
    connections.delete(auth.caller(), id);
    return ResponseEntity.noContent().build();
  }
}
