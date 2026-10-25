package com.meterbook.identity.audit;

import static com.meterbook.identity.db.Tables.AUDIT_EVENTS;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meterbook.identity.db.tables.records.AuditEventsRecord;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads a tenant's audit log, newest first (ADR-0013). */
@Service
public class AuditEvents {

  /** One event as customers see it. */
  public record Event(
      UUID id,
      OffsetDateTime occurredAt,
      String action,
      String outcome,
      Actor actor,
      Target target,
      String requestId,
      Map<String, String> details) {
    public Event {
      details = Map.copyOf(details);
    }
  }

  /** Who acted. */
  public record Actor(String type, UUID id) {}

  /** What the event is about; {@code id} is null when the request named no valid ID. */
  public record Target(String type, @JsonInclude(JsonInclude.Include.ALWAYS) UUID id) {}

  /** Optional filters of {@code GET /v1/audit-events}. */
  public record Filter(Optional<String> action, Optional<UUID> targetId) {}

  /** Where a page starts: after this event, in newest-first order. */
  record Cursor(OffsetDateTime occurredAt, UUID id) {

    String encode() {
      String raw = occurredAt.toInstant() + "|" + id;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String value) {
      try {
        String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        return new Cursor(
            OffsetDateTime.ofInstant(Instant.parse(raw.substring(0, bar)), ZoneOffset.UTC),
            UUID.fromString(raw.substring(bar + 1)));
      } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
        throw ApiException.invalid(
            "cursor_invalid", "starting_after is not a cursor from this API", "starting_after");
      }
    }
  }

  private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {};

  private final DSLContext db;
  private final TenantScope scope;
  private final JsonMapper json;

  public AuditEvents(DSLContext db, TenantScope scope, JsonMapper json) {
    this.db = db;
    this.scope = scope;
    this.json = json;
  }

  public Pages.Page<Event> list(
      Caller caller, Filter filter, Optional<String> startingAfter, int limit) {
    UUID tenant = tenantOf(caller);
    Condition where = AUDIT_EVENTS.TENANT_ID.eq(tenant);
    if (filter.action().isPresent()) {
      where = where.and(AUDIT_EVENTS.ACTION.eq(filter.action().get()));
    }
    if (filter.targetId().isPresent()) {
      where = where.and(AUDIT_EVENTS.TARGET_ID.eq(filter.targetId().get()));
    }
    if (startingAfter.isPresent()) {
      Cursor after = Cursor.decode(startingAfter.get());
      where =
          where.and(
              DSL.row(AUDIT_EVENTS.OCCURRED_AT, AUDIT_EVENTS.ID)
                  .lt(after.occurredAt(), after.id()));
    }
    Condition page = where;
    List<Event> rows =
        scope.inTenant(
            tenant,
            () ->
                db.selectFrom(AUDIT_EVENTS)
                    .where(page)
                    .orderBy(AUDIT_EVENTS.OCCURRED_AT.desc(), AUDIT_EVENTS.ID.desc())
                    .limit(limit + 1)
                    .fetch(this::event));
    return Pages.of(rows, limit, e -> new Cursor(e.occurredAt(), e.id()).encode());
  }

  private Event event(AuditEventsRecord r) {
    return new Event(
        r.getId(),
        r.getOccurredAt(),
        r.getAction(),
        r.getOutcome(),
        new Actor(r.getActorType(), r.getActorId()),
        new Target(r.getTargetType(), r.getTargetId()),
        r.getRequestId(),
        json.readValue(r.getDetails().data(), DETAILS));
  }

  private static UUID tenantOf(Caller caller) {
    UUID tenant =
        caller
            .tenantId()
            .orElseThrow(
                () -> ApiException.forbidden("tenant_required", "audit events belong to a tenant"));
    return tenant;
  }
}
