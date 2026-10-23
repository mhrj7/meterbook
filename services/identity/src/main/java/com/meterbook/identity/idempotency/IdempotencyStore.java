package com.meterbook.identity.idempotency;

import static com.meterbook.identity.db.Tables.IDEMPOTENCY_KEYS;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Idempotency-Key records. Every method runs as its own statement (outside the caller's
 * transaction), so a claim is visible to concurrent requests as soon as it is made.
 */
@Repository
public class IdempotencyStore {

  /** How long a key is remembered (api-standards.md). */
  public static final Duration RETENTION = Duration.ofHours(24);

  /** A stored response. */
  public record StoredResponse(int status, String body, String location) {}

  /** The state of a key after {@link #claim}. */
  public sealed interface Claim {
    /** This request owns the key and must run, then {@link #complete} or {@link #release} it. */
    record Started() implements Claim {}

    /** Another request already completed with this key and body. */
    record Replay(StoredResponse response) implements Claim {}

    /** Another request with this key is still running. */
    record InFlight() implements Claim {}

    /** The key was used with a different request. */
    record Mismatch() implements Claim {}
  }

  private final DSLContext db;
  private final Clock clock;

  public IdempotencyStore(DSLContext db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Claim claim(String scope, String key, String requestHash) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    // An expired record no longer protects anything; drop it so the key can be used again.
    db.deleteFrom(IDEMPOTENCY_KEYS)
        .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope))
        .and(IDEMPOTENCY_KEYS.KEY.eq(key))
        .and(IDEMPOTENCY_KEYS.CREATED_AT.lt(now.minus(RETENTION)))
        .execute();
    int inserted =
        db.insertInto(IDEMPOTENCY_KEYS)
            .set(IDEMPOTENCY_KEYS.SCOPE, scope)
            .set(IDEMPOTENCY_KEYS.KEY, key)
            .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(IDEMPOTENCY_KEYS.CREATED_AT, now)
            .onConflictDoNothing()
            .execute();
    if (inserted == 1) {
      return new Claim.Started();
    }
    var existing =
        db.selectFrom(IDEMPOTENCY_KEYS)
            .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope))
            .and(IDEMPOTENCY_KEYS.KEY.eq(key))
            .fetchOptional();
    if (existing.isEmpty()) {
      // Released between our insert and select: the other request failed; let the client retry.
      return new Claim.InFlight();
    }
    var row = existing.get();
    if (!row.getRequestHash().equals(requestHash)) {
      return new Claim.Mismatch();
    }
    if (row.getStatusCode() == null) {
      return new Claim.InFlight();
    }
    return new Claim.Replay(
        new StoredResponse(row.getStatusCode(), row.getResponseBody(), row.getLocation()));
  }

  public void complete(String scope, String key, StoredResponse response) {
    db.update(IDEMPOTENCY_KEYS)
        .set(IDEMPOTENCY_KEYS.STATUS_CODE, response.status())
        .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, response.body())
        .set(IDEMPOTENCY_KEYS.LOCATION, response.location())
        .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope))
        .and(IDEMPOTENCY_KEYS.KEY.eq(key))
        .execute();
  }

  public void release(String scope, String key) {
    db.deleteFrom(IDEMPOTENCY_KEYS)
        .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope))
        .and(IDEMPOTENCY_KEYS.KEY.eq(key))
        .and(IDEMPOTENCY_KEYS.STATUS_CODE.isNull())
        .execute();
  }

  /** Deletes records past their retention; returns how many. */
  public int purgeExpired() {
    return db.deleteFrom(IDEMPOTENCY_KEYS)
        .where(IDEMPOTENCY_KEYS.CREATED_AT.lt(OffsetDateTime.now(clock).minus(RETENTION)))
        .execute();
  }

  Optional<String> hashOf(String scope, String key) {
    return db.select(IDEMPOTENCY_KEYS.REQUEST_HASH)
        .from(IDEMPOTENCY_KEYS)
        .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope))
        .and(IDEMPOTENCY_KEYS.KEY.eq(key))
        .fetchOptional(IDEMPOTENCY_KEYS.REQUEST_HASH);
  }
}
