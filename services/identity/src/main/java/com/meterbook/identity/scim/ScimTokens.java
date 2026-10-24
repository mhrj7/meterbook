package com.meterbook.identity.scim;

import static com.meterbook.identity.db.Tables.SCIM_TOKENS;

import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.Role;
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
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * Tenant SCIM tokens: {@code mbscim_} plus 32 random bytes in base62. The secret is returned once;
 * only its SHA-256 hash is stored, so a database leak does not leak working credentials.
 */
@Service
public class ScimTokens {

  static final String PREFIX = "mbscim_";
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int SECRET_CHARACTERS = 43; // 62^43 > 2^256
  private static final int SHOWN_PREFIX = 12;

  /** last_used_at is a hint for admins; writing it at most once a minute keeps reads cheap. */
  private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

  /** A token as admins see it. */
  public record Token(
      UUID id,
      String description,
      String prefix,
      OffsetDateTime createdAt,
      OffsetDateTime lastUsedAt) {}

  /** A newly issued token with its secret. */
  public record Issued(Token token, String secret) {}

  /** The tenant a valid token belongs to. */
  public record Holder(UUID tokenId, UUID tenantId) {}

  private final DSLContext db;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ScimTokens(DSLContext db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Issued issue(Caller caller, String description) {
    UUID tenant = tenantAdminTenant(caller);
    String secret = PREFIX + randomSecret();
    Token token =
        new Token(
            UUID.randomUUID(),
            description,
            secret.substring(0, SHOWN_PREFIX),
            OffsetDateTime.now(clock),
            null);
    db.insertInto(SCIM_TOKENS)
        .set(SCIM_TOKENS.ID, token.id())
        .set(SCIM_TOKENS.TENANT_ID, tenant)
        .set(SCIM_TOKENS.DESCRIPTION, description)
        .set(SCIM_TOKENS.PREFIX, token.prefix())
        .set(SCIM_TOKENS.TOKEN_HASH, hash(secret))
        .set(SCIM_TOKENS.CREATED_AT, token.createdAt())
        .execute();
    return new Issued(token, secret);
  }

  public Pages.Page<Token> list(Caller caller, Optional<UUID> startingAfter, int limit) {
    UUID tenant = tenantAdminTenant(caller);
    var condition = SCIM_TOKENS.TENANT_ID.eq(tenant).and(SCIM_TOKENS.REVOKED_AT.isNull());
    if (startingAfter.isPresent()) {
      condition = condition.and(SCIM_TOKENS.ID.gt(startingAfter.get()));
    }
    List<Token> rows =
        db.selectFrom(SCIM_TOKENS)
            .where(condition)
            .orderBy(SCIM_TOKENS.ID)
            .limit(limit + 1)
            .fetch(
                r ->
                    new Token(
                        r.getId(),
                        r.getDescription(),
                        r.getPrefix(),
                        r.getCreatedAt(),
                        r.getLastUsedAt()));
    return Pages.of(rows, limit, t -> t.id().toString());
  }

  public void revoke(Caller caller, UUID id) {
    UUID tenant = tenantAdminTenant(caller);
    int revoked =
        db.update(SCIM_TOKENS)
            .set(SCIM_TOKENS.REVOKED_AT, OffsetDateTime.now(clock))
            .where(SCIM_TOKENS.TENANT_ID.eq(tenant))
            .and(SCIM_TOKENS.ID.eq(id))
            .and(SCIM_TOKENS.REVOKED_AT.isNull())
            .execute();
    if (revoked == 0) {
      throw ApiException.notFound("scim_token_not_found", "no such SCIM token");
    }
  }

  /** The tenant behind a presented secret, if it is a live token. Checked on every request. */
  public Optional<Holder> authenticate(String secret) {
    if (secret == null
        || !secret.startsWith(PREFIX)
        || secret.length() != PREFIX.length() + SECRET_CHARACTERS) {
      return Optional.empty();
    }
    var row =
        db.selectFrom(SCIM_TOKENS)
            .where(SCIM_TOKENS.TOKEN_HASH.eq(hash(secret)))
            .and(SCIM_TOKENS.REVOKED_AT.isNull())
            .fetchOptional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    var token = row.get();
    if (token.getLastUsedAt() == null
        || token.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
      db.update(SCIM_TOKENS)
          .set(SCIM_TOKENS.LAST_USED_AT, now)
          .where(SCIM_TOKENS.ID.eq(token.getId()))
          .execute();
    }
    return Optional.of(new Holder(token.getId(), token.getTenantId()));
  }

  private String randomSecret() {
    StringBuilder out = new StringBuilder(SECRET_CHARACTERS);
    for (int i = 0; i < SECRET_CHARACTERS; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.toString();
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

  private static UUID tenantAdminTenant(Caller caller) {
    if (!caller.has(Role.TENANT_ADMIN)) {
      throw ApiException.forbidden(
          "tenant_admin_required", "only tenant administrators manage SCIM tokens");
    }
    return caller
        .tenantId()
        .orElseThrow(
            () -> ApiException.forbidden("tenant_required", "SCIM tokens belong to a tenant"));
  }
}
