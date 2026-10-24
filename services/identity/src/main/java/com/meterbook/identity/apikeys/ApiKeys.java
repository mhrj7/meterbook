package com.meterbook.identity.apikeys;

import static com.meterbook.identity.db.Tables.API_KEYS;
import static com.meterbook.identity.db.Tables.ENVIRONMENTS;

import com.meterbook.identity.db.tables.records.ApiKeysRecord;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * Environment-scoped API keys (ADR-0010): {@code mb_live_} or {@code mb_test_}, 40 random base62
 * characters and a 6-character base62 CRC-32 of the rest. The key is returned once; only its
 * SHA-256 hash is stored.
 */
@Service
public class ApiKeys {

  /** What a key may do; services enforce the scopes their endpoints need. */
  public static final Set<String> SCOPES =
      Set.of(
          "usage:write",
          "usage:read",
          "customers:read",
          "customers:write",
          "invoices:read",
          "invoices:write",
          "catalog:read",
          "catalog:write");

  static final int MAX_KEYS_PER_ENVIRONMENT = 50;
  static final Duration MAX_LIFETIME = Duration.ofDays(730);
  static final Duration DEFAULT_GRACE_PERIOD = Duration.ofHours(24);

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int RANDOM_CHARACTERS = 40; // 62^40 > 2^238
  private static final int CHECKSUM_CHARACTERS = 6; // 62^6 > 2^32
  private static final int SHOWN_PREFIX = 12;

  /** last_used_at is a hint for admins; writing it at most once a minute keeps reads cheap. */
  private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

  /** A key as admins see it. */
  public record Key(
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
      UUID rotatedTo) {}

  /** A newly created key with its secret. */
  public record Issued(Key key, String secret) {}

  /** Who holds a live key. */
  public record Holder(
      UUID keyId,
      UUID tenantId,
      UUID projectId,
      UUID environmentId,
      String kind,
      List<String> scopes,
      OffsetDateTime expiresAt) {}

  private final DSLContext db;
  private final TenantScope scope;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ApiKeys(DSLContext db, TenantScope scope, Clock clock) {
    this.db = db;
    this.scope = scope;
    this.clock = clock;
  }

  public Issued create(
      Caller caller,
      UUID projectId,
      UUID environmentId,
      String name,
      List<String> scopes,
      Optional<OffsetDateTime> expiresAt) {
    UUID tenant = adminTenant(caller);
    List<String> checked = checkedScopes(scopes);
    OffsetDateTime now = OffsetDateTime.now(clock);
    expiresAt.ifPresent(at -> checkExpiry(at, now));
    return scope.inTenant(
        tenant,
        () -> {
          String kind = lockEnvironment(tenant, projectId, environmentId);
          // Expired keys do not count; rotation may briefly exceed the limit, as its successor
          // replaces a key.
          if (db.fetchCount(
                  API_KEYS,
                  live(tenant, projectId, environmentId)
                      .and(API_KEYS.EXPIRES_AT.isNull().or(API_KEYS.EXPIRES_AT.gt(now))))
              >= MAX_KEYS_PER_ENVIRONMENT) {
            throw ApiException.invalid(
                "api_key_limit_reached",
                "an environment can have at most "
                    + MAX_KEYS_PER_ENVIRONMENT
                    + " keys; revoke unused ones first",
                null);
          }
          return insert(
              tenant, projectId, environmentId, kind, name, checked, expiresAt.orElse(null), now);
        });
  }

  public Pages.Page<Key> list(
      Caller caller, UUID projectId, UUID environmentId, Optional<UUID> startingAfter, int limit) {
    UUID tenant = memberTenant(caller);
    Condition where = live(tenant, projectId, environmentId);
    if (startingAfter.isPresent()) {
      where = where.and(API_KEYS.ID.gt(startingAfter.get()));
    }
    Condition page = where;
    List<Key> rows =
        scope.inTenant(
            tenant,
            () -> {
              requireEnvironment(tenant, projectId, environmentId);
              return db.selectFrom(API_KEYS)
                  .where(page)
                  .orderBy(API_KEYS.ID)
                  .limit(limit + 1)
                  .fetch(ApiKeys::key);
            });
    return Pages.of(rows, limit, k -> k.id().toString());
  }

  public Key get(Caller caller, UUID projectId, UUID environmentId, UUID id) {
    UUID tenant = memberTenant(caller);
    return scope.inTenant(
        tenant,
        () ->
            db.selectFrom(API_KEYS)
                .where(live(tenant, projectId, environmentId))
                .and(API_KEYS.ID.eq(id))
                .fetchOptional(ApiKeys::key)
                .orElseThrow(ApiKeys::keyNotFound));
  }

  /** Revokes a key at once; identity rejects it from the next request. */
  public void revoke(Caller caller, UUID projectId, UUID environmentId, UUID id) {
    UUID tenant = adminTenant(caller);
    int revoked =
        scope.inTenant(
            tenant,
            () ->
                db.update(API_KEYS)
                    .set(API_KEYS.REVOKED_AT, OffsetDateTime.now(clock))
                    .where(live(tenant, projectId, environmentId))
                    .and(API_KEYS.ID.eq(id))
                    .execute());
    if (revoked == 0) {
      throw keyNotFound();
    }
  }

  /**
   * Issues a successor with the same name, scopes and expiry; the old key keeps working until the
   * grace period ends, or its own earlier expiry.
   */
  public Issued rotate(
      Caller caller, UUID projectId, UUID environmentId, UUID id, Duration gracePeriod) {
    UUID tenant = adminTenant(caller);
    OffsetDateTime now = OffsetDateTime.now(clock);
    return scope.inTenant(
        tenant,
        () -> {
          String kind = lockEnvironment(tenant, projectId, environmentId);
          ApiKeysRecord old =
              db.selectFrom(API_KEYS)
                  .where(live(tenant, projectId, environmentId))
                  .and(API_KEYS.ID.eq(id))
                  .forUpdate()
                  .fetchOptional()
                  .orElseThrow(ApiKeys::keyNotFound);
          if (old.getRotatedTo() != null) {
            throw ApiException.conflict(
                "api_key_already_rotated",
                "this key was already replaced by " + old.getRotatedTo() + "; rotate that key",
                null);
          }
          Issued successor =
              insert(
                  tenant,
                  projectId,
                  environmentId,
                  kind,
                  old.getName(),
                  List.of(old.getScopes()),
                  old.getExpiresAt(),
                  now);
          OffsetDateTime graceEnds = now.plus(gracePeriod);
          OffsetDateTime oldExpiry =
              old.getExpiresAt() != null && old.getExpiresAt().isBefore(graceEnds)
                  ? old.getExpiresAt()
                  : graceEnds;
          db.update(API_KEYS)
              .set(API_KEYS.ROTATED_TO, successor.key().id())
              .set(API_KEYS.EXPIRES_AT, oldExpiry)
              .where(API_KEYS.ID.eq(id))
              .execute();
          return successor;
        });
  }

  /** The holder of a presented key, if it is live: not revoked and not expired. */
  public Optional<Holder> authenticate(String secret) {
    if (!wellFormed(secret)) {
      return Optional.empty();
    }
    // A presented key does not say which tenant it belongs to; finding out is a platform operation.
    return scope.asPlatform("resolve an API key to its tenant", () -> resolve(secret));
  }

  private Optional<Holder> resolve(String secret) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var row =
        db.selectFrom(API_KEYS)
            .where(API_KEYS.KEY_HASH.eq(hash(secret)))
            .and(API_KEYS.REVOKED_AT.isNull())
            .and(API_KEYS.EXPIRES_AT.isNull().or(API_KEYS.EXPIRES_AT.gt(now)))
            .fetchOptional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    var key = row.get();
    if (key.getLastUsedAt() == null
        || key.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
      db.update(API_KEYS)
          .set(API_KEYS.LAST_USED_AT, now)
          .where(API_KEYS.ID.eq(key.getId()))
          .execute();
    }
    return Optional.of(
        new Holder(
            key.getId(),
            key.getTenantId(),
            key.getProjectId(),
            key.getEnvironmentId(),
            key.getKind(),
            List.of(key.getScopes()),
            key.getExpiresAt()));
  }

  private Issued insert(
      UUID tenant,
      UUID projectId,
      UUID environmentId,
      String kind,
      String name,
      List<String> scopes,
      OffsetDateTime expiresAt,
      OffsetDateTime now) {
    String secret = newSecret(kind);
    var record =
        db.insertInto(API_KEYS)
            .set(API_KEYS.ID, UUID.randomUUID())
            .set(API_KEYS.TENANT_ID, tenant)
            .set(API_KEYS.PROJECT_ID, projectId)
            .set(API_KEYS.ENVIRONMENT_ID, environmentId)
            .set(API_KEYS.KIND, kind)
            .set(API_KEYS.NAME, name)
            .set(API_KEYS.PREFIX, secret.substring(0, SHOWN_PREFIX))
            .set(API_KEYS.KEY_HASH, hash(secret))
            .set(API_KEYS.SCOPES, scopes.toArray(String[]::new))
            .set(API_KEYS.CREATED_AT, now)
            .set(API_KEYS.EXPIRES_AT, expiresAt)
            .returning()
            .fetchOne();
    return new Issued(key(record), secret);
  }

  /** Locks the environment so concurrent creations cannot overshoot the limit; returns its kind. */
  private String lockEnvironment(UUID tenant, UUID projectId, UUID environmentId) {
    return db.select(ENVIRONMENTS.KIND)
        .from(ENVIRONMENTS)
        .where(ENVIRONMENTS.ID.eq(environmentId))
        .and(ENVIRONMENTS.PROJECT_ID.eq(projectId))
        .and(ENVIRONMENTS.TENANT_ID.eq(tenant))
        .forUpdate()
        .fetchOptional(ENVIRONMENTS.KIND)
        .orElseThrow(ApiKeys::environmentNotFound);
  }

  private void requireEnvironment(UUID tenant, UUID projectId, UUID environmentId) {
    if (!db.fetchExists(
        ENVIRONMENTS,
        ENVIRONMENTS
            .ID
            .eq(environmentId)
            .and(ENVIRONMENTS.PROJECT_ID.eq(projectId))
            .and(ENVIRONMENTS.TENANT_ID.eq(tenant)))) {
      throw environmentNotFound();
    }
  }

  private static Condition live(UUID tenant, UUID projectId, UUID environmentId) {
    return API_KEYS
        .TENANT_ID
        .eq(tenant)
        .and(API_KEYS.PROJECT_ID.eq(projectId))
        .and(API_KEYS.ENVIRONMENT_ID.eq(environmentId))
        .and(API_KEYS.REVOKED_AT.isNull());
  }

  private static List<String> checkedScopes(List<String> scopes) {
    TreeSet<String> unique = new TreeSet<>(scopes);
    if (unique.size() != scopes.size()) {
      throw ApiException.invalid("scopes_duplicated", "each scope may appear once", "scopes");
    }
    for (String s : unique) {
      if (!SCOPES.contains(s)) {
        throw ApiException.invalid("scope_unknown", "unknown scope: " + s, "scopes");
      }
    }
    return List.copyOf(unique);
  }

  private static void checkExpiry(OffsetDateTime expiresAt, OffsetDateTime now) {
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
      throw ApiException.invalid(
          "expires_at_out_of_range",
          "expires_at must be in the future and at most 2 years ahead",
          "expires_at");
    }
  }

  private String newSecret(String kind) {
    StringBuilder out = new StringBuilder("mb_").append(kind).append('_');
    for (int i = 0; i < RANDOM_CHARACTERS; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.append(checksum(out)).toString();
  }

  /** Format and checksum only: rejects typos and random strings without touching the database. */
  static boolean wellFormed(String secret) {
    if (secret == null
        || !(secret.startsWith("mb_live_") || secret.startsWith("mb_test_"))
        || secret.length() != 8 + RANDOM_CHARACTERS + CHECKSUM_CHARACTERS) {
      return false;
    }
    for (int i = 8; i < secret.length(); i++) {
      if (ALPHABET.indexOf(secret.charAt(i)) < 0) {
        return false;
      }
    }
    int body = secret.length() - CHECKSUM_CHARACTERS;
    return checksum(secret.substring(0, body)).equals(secret.substring(body));
  }

  static String checksum(CharSequence body) {
    CRC32 crc = new CRC32();
    crc.update(body.toString().getBytes(StandardCharsets.US_ASCII));
    long value = crc.getValue();
    char[] out = new char[CHECKSUM_CHARACTERS];
    for (int i = CHECKSUM_CHARACTERS - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (value % ALPHABET.length()));
      value /= ALPHABET.length();
    }
    return new String(out);
  }

  static String hash(String secret) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }

  private static Key key(ApiKeysRecord r) {
    return new Key(
        r.getId(),
        r.getProjectId(),
        r.getEnvironmentId(),
        r.getKind(),
        r.getName(),
        r.getPrefix(),
        List.of(r.getScopes()),
        r.getCreatedAt(),
        r.getExpiresAt(),
        r.getLastUsedAt(),
        r.getRotatedTo());
  }

  private static ApiException keyNotFound() {
    return ApiException.notFound("api_key_not_found", "no such API key");
  }

  private static ApiException environmentNotFound() {
    return ApiException.notFound("environment_not_found", "no such environment");
  }

  private static UUID adminTenant(Caller caller) {
    UUID tenant = memberTenant(caller);
    if (!caller.has(Role.TENANT_ADMIN)) {
      throw ApiException.forbidden(
          "tenant_admin_required", "only tenant administrators manage API keys");
    }
    return tenant;
  }

  private static UUID memberTenant(Caller caller) {
    return caller
        .tenantId()
        .orElseThrow(
            () -> ApiException.forbidden("tenant_required", "API keys belong to a tenant"));
  }
}
