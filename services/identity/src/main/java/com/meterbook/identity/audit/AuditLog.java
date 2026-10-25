package com.meterbook.identity.audit;

import static com.meterbook.identity.db.Tables.AUDIT_EVENTS;

import com.meterbook.identity.scim.ScimAuthentication;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.web.RequestIdFilter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit events (ADR-0013). Services call {@link #record} inside the {@code TenantScope}
 * transaction that makes the change, so the change and its event commit or roll back together. The
 * actor is the authenticated caller and the request ID comes from the logging context; neither is
 * ever taken from the request body.
 */
@Component
public class AuditLog {

  /** What became of an attempt. */
  public enum Outcome {
    SUCCESS("success"),
    DENIED("denied"),
    NOT_FOUND("not_found");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** Who acted: a user, a tenant's SCIM client, or one of our operators. IDs only, never PII. */
  public record Actor(String type, UUID id) {

    /** The authenticated caller of the current request. */
    static Actor current() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      return switch (authentication) {
        case CallerAuthentication caller ->
            new Actor(
                caller.caller().tenantId().isEmpty() && caller.caller().has(Role.PLATFORM_ADMIN)
                    ? "platform_operator"
                    : "user",
                caller.caller().userId());
        case ScimAuthentication scim -> new Actor("scim_token", scim.tokenId());
        case null, default ->
            throw new IllegalStateException("an audited change needs an authenticated actor");
      };
    }
  }

  static final String NO_REQUEST = "none";

  /** The actor of changes no person made, such as a one-off backfill. */
  public static final UUID SYSTEM = new UUID(0, 0);

  private final DSLContext db;
  private final JsonMapper json;
  private final Clock clock;

  public AuditLog(DSLContext db, JsonMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  /** Records a successful change; call it inside the transaction that makes the change. */
  public void record(UUID tenant, AuditAction action, UUID target) {
    record(tenant, action, target, Map.of());
  }

  /**
   * Records a successful change with details: non-secret identifiers and the names of changed
   * fields, never values a customer typed.
   */
  public void record(UUID tenant, AuditAction action, UUID target, Map<String, String> details) {
    if (!action.isChange()) {
      throw new IllegalArgumentException(action.code() + " is not a change");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "an audit event must be written in the transaction of its change");
    }
    insert(
        tenant,
        Actor.current(),
        action.code(),
        Outcome.SUCCESS,
        action.targetType(),
        target,
        details);
  }

  /**
   * Records a change the service made on its own, with no request behind it (a backfill), as {@code
   * system}.
   */
  public void recordAsSystem(
      UUID tenant, AuditAction action, UUID target, Map<String, String> details) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "an audit event must be written in the transaction of its change");
    }
    insert(
        tenant,
        new Actor("system", SYSTEM),
        action.code(),
        Outcome.SUCCESS,
        action.targetType(),
        target,
        details);
  }

  /** Records a refused attempt; {@link AuditInterceptor} calls it in a transaction of its own. */
  void refused(UUID tenant, AuditAction action, Outcome outcome, String targetType, UUID target) {
    insert(tenant, Actor.current(), action.code(), outcome, targetType, target, Map.of());
  }

  private void insert(
      UUID tenant,
      Actor actor,
      String action,
      Outcome outcome,
      String targetType,
      UUID target,
      Map<String, String> details) {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    db.insertInto(AUDIT_EVENTS)
        .set(AUDIT_EVENTS.ID, UUID.randomUUID())
        .set(AUDIT_EVENTS.TENANT_ID, tenant)
        .set(AUDIT_EVENTS.OCCURRED_AT, OffsetDateTime.now(clock))
        .set(AUDIT_EVENTS.ACTION, action)
        .set(AUDIT_EVENTS.OUTCOME, outcome.value())
        .set(AUDIT_EVENTS.ACTOR_TYPE, actor.type())
        .set(AUDIT_EVENTS.ACTOR_ID, actor.id())
        .set(AUDIT_EVENTS.TARGET_TYPE, targetType)
        .set(AUDIT_EVENTS.TARGET_ID, target)
        .set(AUDIT_EVENTS.REQUEST_ID, requestId == null ? NO_REQUEST : requestId)
        .set(AUDIT_EVENTS.DETAILS, JSON.valueOf(json.writeValueAsString(details)))
        .execute();
  }
}
