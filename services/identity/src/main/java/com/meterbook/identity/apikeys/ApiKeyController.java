package com.meterbook.identity.apikeys;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.Audited;
import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API keys of an environment, and {@code /v1/api-key} (contracts/openapi/identity-v1.yaml). */
@RestController
public class ApiKeyController {

  /** How long a caller may trust a verification; revocation reaches every service within it. */
  static final Duration VERIFICATION_CACHE = Duration.ofSeconds(30);

  private static final String KEYS =
      "/v1/projects/{projectId}/environments/{environmentId}/api-keys";

  /** Request body of key creation. */
  public record CreateApiKey(
      @NotBlank @Size(max = 200) String name,
      @NotEmpty List<@NotNull String> scopes,
      OffsetDateTime expiresAt) {
    public CreateApiKey {
      scopes = scopes == null ? null : Collections.unmodifiableList(new ArrayList<>(scopes));
    }
  }

  /** Request body of rotation. */
  public record RotateApiKey(@Min(0) @Max(604800) Integer gracePeriodSeconds) {}

  /** A key without its secret. */
  public record ApiKey(
      UUID id,
      UUID projectId,
      UUID environmentId,
      String kind,
      String name,
      String prefix,
      List<String> scopes,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt,
      OffsetDateTime lastUsedAt,
      UUID rotatedTo) {
    public ApiKey {
      scopes = List.copyOf(scopes);
    }

    static ApiKey of(ApiKeys.Key k) {
      return new ApiKey(
          k.id(),
          k.projectId(),
          k.environmentId(),
          k.kind(),
          k.name(),
          k.prefix(),
          k.scopes(),
          k.createdAt(),
          k.expiresAt(),
          k.lastUsedAt(),
          k.rotatedTo());
    }
  }

  /** A newly created key; the only time the secret is shown. */
  public record ApiKeyWithSecret(
      UUID id,
      UUID projectId,
      UUID environmentId,
      String kind,
      String name,
      String prefix,
      List<String> scopes,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt,
      String secret) {
    public ApiKeyWithSecret {
      scopes = List.copyOf(scopes);
    }

    static ApiKeyWithSecret of(ApiKeys.Issued issued) {
      var k = issued.key();
      return new ApiKeyWithSecret(
          k.id(),
          k.projectId(),
          k.environmentId(),
          k.kind(),
          k.name(),
          k.prefix(),
          k.scopes(),
          k.createdAt(),
          k.expiresAt(),
          issued.secret());
    }
  }

  /** A page. */
  public record ApiKeyList(List<ApiKey> data, boolean hasMore, String nextCursor) {
    public ApiKeyList {
      data = List.copyOf(data);
    }
  }

  /** What the presenting key is and may do. */
  public record CurrentApiKey(
      UUID id,
      UUID tenantId,
      UUID projectId,
      UUID environmentId,
      String kind,
      List<String> scopes,
      OffsetDateTime expiresAt) {
    public CurrentApiKey {
      scopes = List.copyOf(scopes);
    }
  }

  private final ApiKeys keys;
  private final Idempotency idempotency;

  public ApiKeyController(ApiKeys keys, Idempotency idempotency) {
    this.keys = keys;
    this.idempotency = idempotency;
  }

  @PostMapping(KEYS)
  @Audited(
      value = AuditAction.API_KEY_CREATED,
      targetVariable = "environmentId",
      targetType = "environment")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @PathVariable UUID projectId,
      @PathVariable UUID environmentId,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateApiKey body) {
    // The secret must not be stored anywhere, including the idempotency record.
    return idempotency.execute(
        auth.getName(),
        key,
        "POST " + path(projectId, environmentId),
        body,
        Idempotency.Storage.DO_NOT_STORE_RESPONSE,
        () -> {
          var issued =
              keys.create(
                  auth.caller(),
                  projectId,
                  environmentId,
                  body.name(),
                  body.scopes(),
                  Optional.ofNullable(body.expiresAt()));
          return created(projectId, environmentId, issued);
        });
  }

  @GetMapping(KEYS)
  @Audited(
      value = AuditAction.API_KEY_READ,
      targetVariable = "environmentId",
      targetType = "environment")
  public ApiKeyList list(
      CallerAuthentication auth,
      @PathVariable UUID projectId,
      @PathVariable UUID environmentId,
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
    var page = keys.list(auth.caller(), projectId, environmentId, cursor, limit);
    return new ApiKeyList(
        page.data().stream().map(ApiKey::of).toList(), page.hasMore(), page.nextCursor());
  }

  @GetMapping(KEYS + "/{id}")
  @Audited(AuditAction.API_KEY_READ)
  public ApiKey get(
      CallerAuthentication auth,
      @PathVariable UUID projectId,
      @PathVariable UUID environmentId,
      @PathVariable UUID id) {
    return ApiKey.of(keys.get(auth.caller(), projectId, environmentId, id));
  }

  @DeleteMapping(KEYS + "/{id}")
  @Audited(AuditAction.API_KEY_REVOKED)
  public ResponseEntity<Void> revoke(
      CallerAuthentication auth,
      @PathVariable UUID projectId,
      @PathVariable UUID environmentId,
      @PathVariable UUID id) {
    keys.revoke(auth.caller(), projectId, environmentId, id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(KEYS + "/{id}/rotate")
  @Audited(AuditAction.API_KEY_ROTATED)
  public ResponseEntity<?> rotate(
      CallerAuthentication auth,
      @PathVariable UUID projectId,
      @PathVariable UUID environmentId,
      @PathVariable UUID id,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody RotateApiKey body) {
    Duration grace =
        body.gracePeriodSeconds() == null
            ? ApiKeys.DEFAULT_GRACE_PERIOD
            : Duration.ofSeconds(body.gracePeriodSeconds());
    return idempotency.execute(
        auth.getName(),
        key,
        "POST " + path(projectId, environmentId) + "/" + id + "/rotate",
        body,
        Idempotency.Storage.DO_NOT_STORE_RESPONSE,
        () ->
            created(
                projectId,
                environmentId,
                keys.rotate(auth.caller(), projectId, environmentId, id, grace)));
  }

  @GetMapping("/v1/api-key")
  public ResponseEntity<CurrentApiKey> current(ApiKeyAuthentication auth) {
    var h = auth.holder();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(VERIFICATION_CACHE).cachePrivate())
        .body(
            new CurrentApiKey(
                h.keyId(),
                h.tenantId(),
                h.projectId(),
                h.environmentId(),
                h.kind(),
                h.scopes(),
                h.expiresAt()));
  }

  private static ResponseEntity<?> created(
      UUID projectId, UUID environmentId, ApiKeys.Issued issued) {
    return ResponseEntity.created(
            URI.create(path(projectId, environmentId) + "/" + issued.key().id()))
        .body(ApiKeyWithSecret.of(issued));
  }

  private static String path(UUID projectId, UUID environmentId) {
    return "/v1/projects/" + projectId + "/environments/" + environmentId + "/api-keys";
  }
}
