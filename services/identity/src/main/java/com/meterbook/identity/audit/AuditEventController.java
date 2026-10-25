package com.meterbook.identity.audit;

import com.meterbook.identity.authz.Permission;
import com.meterbook.identity.authz.Requires;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/audit-events} (contracts/openapi/identity-v1.yaml). */
@RestController
public class AuditEventController {

  private static final Pattern ACTION = Pattern.compile("^[a-z_]+\\.[a-z_]+$");

  /** A page of audit events. */
  public record AuditEventList(List<AuditEvents.Event> data, boolean hasMore, String nextCursor) {
    public AuditEventList {
      data = List.copyOf(data);
    }
  }

  private final AuditEvents events;

  public AuditEventController(AuditEvents events) {
    this.events = events;
  }

  @GetMapping("/v1/audit-events")
  @Requires(Permission.AUDIT_EVENTS_READ)
  @Audited(value = AuditAction.AUDIT_EVENTS_READ, targetVariable = "")
  public AuditEventList list(
      CallerAuthentication auth,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "starting_after", required = false) String startingAfter,
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "target_id", required = false) String targetId) {
    if (limit < 1 || limit > 200) {
      throw ApiException.invalid("limit_out_of_range", "limit must be between 1 and 200", "limit");
    }
    if (action != null && !ACTION.matcher(action).matches()) {
      throw ApiException.invalid("action_invalid", "action looks like project.updated", "action");
    }
    Pages.Page<AuditEvents.Event> page =
        events.list(
            auth.caller(),
            new AuditEvents.Filter(Optional.ofNullable(action), targetId(targetId)),
            Optional.ofNullable(startingAfter),
            limit);
    return new AuditEventList(page.data(), page.hasMore(), page.nextCursor());
  }

  private static Optional<UUID> targetId(String value) {
    try {
      return Optional.ofNullable(value).map(UUID::fromString);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("target_id_invalid", "target_id must be a UUID", "target_id");
    }
  }
}
