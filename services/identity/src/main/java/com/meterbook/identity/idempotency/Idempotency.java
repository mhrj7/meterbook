package com.meterbook.identity.idempotency;

import com.meterbook.identity.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a mutating request at most once per {@code Idempotency-Key} (api-standards.md): the same key
 * and body within 24 hours return the stored response; a different body is 422; a concurrent
 * request with the same key is 409 with {@code Retry-After}. Only successful responses are stored;
 * a failed attempt releases the key so the client can retry.
 */
@Component
public class Idempotency {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";
  static final int MAX_KEY_LENGTH = 255;

  private final IdempotencyStore store;
  private final JsonMapper json;

  public Idempotency(IdempotencyStore store, JsonMapper json) {
    this.store = store;
    this.json = json;
  }

  /**
   * Runs {@code action} unless this key already has a result, which is then returned instead.
   *
   * @param scope who the key belongs to (the caller's user ID)
   * @param operation method and path, so one key cannot be replayed against another endpoint
   * @param request the parsed request body, hashed to detect a reused key
   */
  public ResponseEntity<?> execute(
      String scope,
      String key,
      String operation,
      Object request,
      Supplier<ResponseEntity<?>> action) {
    validate(key);
    String hash = hash(operation, request);
    return switch (store.claim(scope, key, hash)) {
      case IdempotencyStore.Claim.Started started -> run(scope, key, action);
      case IdempotencyStore.Claim.Replay replay -> replay(replay.response());
      case IdempotencyStore.Claim.InFlight inFlight ->
          throw ApiException.conflict(
              "idempotency_key_in_flight",
              "a request with this Idempotency-Key is still running",
              1);
      case IdempotencyStore.Claim.Mismatch mismatch ->
          throw ApiException.invalid(
              "idempotency_key_reused",
              "this Idempotency-Key was used with a different request",
              HEADER);
    };
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
  void purge() {
    store.purgeExpired();
  }

  private ResponseEntity<?> run(String scope, String key, Supplier<ResponseEntity<?>> action) {
    ResponseEntity<?> response;
    try {
      response = action.get();
    } catch (RuntimeException e) {
      store.release(scope, key);
      throw e;
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      store.release(scope, key);
      return response;
    }
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    store.complete(
        scope,
        key,
        new IdempotencyStore.StoredResponse(
            response.getStatusCode().value(),
            json.writeValueAsString(response.getBody()),
            location));
    return response;
  }

  private static ResponseEntity<?> replay(IdempotencyStore.StoredResponse stored) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(stored.status())
            .contentType(MediaType.APPLICATION_JSON)
            .header(REPLAYED_HEADER, "true");
    if (stored.location() != null) {
      builder.header(HttpHeaders.LOCATION, stored.location());
    }
    return builder.body(stored.body());
  }

  private static void validate(String key) {
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest(
          "idempotency_key_required", "this endpoint requires an Idempotency-Key header", HEADER);
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw ApiException.badRequest(
          "idempotency_key_too_long", "Idempotency-Key may be at most 255 characters", HEADER);
    }
  }

  private String hash(String operation, Object request) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update(operation.getBytes(StandardCharsets.UTF_8));
      sha256.update((byte) '\n');
      sha256.update(json.writeValueAsBytes(request));
      return HexFormat.of().formatHex(sha256.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }
}
