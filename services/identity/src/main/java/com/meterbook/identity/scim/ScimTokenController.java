package com.meterbook.identity.scim;

import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** {@code /v1/scim-tokens} (contracts/openapi/identity-v1.yaml). */
@RestController
public class ScimTokenController {

  /** Request body. */
  public record CreateScimToken(@NotBlank @Size(max = 200) String description) {}

  /** A token without its secret. */
  public record ScimToken(
      UUID id,
      String description,
      String prefix,
      OffsetDateTime createdAt,
      OffsetDateTime lastUsedAt) {
    static ScimToken of(ScimTokens.Token t) {
      return new ScimToken(t.id(), t.description(), t.prefix(), t.createdAt(), t.lastUsedAt());
    }
  }

  /** A newly issued token; the only time the secret is shown. */
  public record ScimTokenWithSecret(
      UUID id, String description, String prefix, OffsetDateTime createdAt, String secret) {}

  /** A page. */
  public record ScimTokenList(List<ScimToken> data, boolean hasMore, String nextCursor) {}

  private final ScimTokens tokens;
  private final Idempotency idempotency;

  public ScimTokenController(ScimTokens tokens, Idempotency idempotency) {
    this.tokens = tokens;
    this.idempotency = idempotency;
  }

  @PostMapping("/v1/scim-tokens")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateScimToken body) {
    // The secret must not be stored anywhere, including the idempotency record.
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/scim-tokens",
        body,
        Idempotency.Storage.DO_NOT_STORE_RESPONSE,
        () -> {
          ScimTokens.Issued issued = tokens.issue(auth.caller(), body.description());
          var t = issued.token();
          return ResponseEntity.created(URI.create("/v1/scim-tokens/" + t.id()))
              .body(
                  new ScimTokenWithSecret(
                      t.id(), t.description(), t.prefix(), t.createdAt(), issued.secret()));
        });
  }

  @GetMapping("/v1/scim-tokens")
  public ScimTokenList list(
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
    var page = tokens.list(auth.caller(), cursor, limit);
    return new ScimTokenList(
        page.data().stream().map(ScimToken::of).toList(), page.hasMore(), page.nextCursor());
  }

  @DeleteMapping("/v1/scim-tokens/{id}")
  public ResponseEntity<Void> revoke(CallerAuthentication auth, @PathVariable UUID id) {
    tokens.revoke(auth.caller(), id);
    return ResponseEntity.noContent().build();
  }
}
