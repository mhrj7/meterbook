package com.meterbook.identity.audit;

import com.meterbook.identity.scim.ScimAuthentication;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.tenancy.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records refused calls to {@link Audited} endpoints (ADR-0013): 403 as {@code denied}, 404 as
 * {@code not_found}, in the caller's own tenant. Runs after the response is decided, in a
 * transaction of its own, since the request's transaction has rolled back. The event is the same
 * whether or not the target exists elsewhere, so the log reveals nothing the response did not.
 */
public class AuditInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

  private final AuditLog audit;
  private final TenantScope scope;

  public AuditInterceptor(AuditLog audit, TenantScope scope) {
    this.audit = audit;
    this.scope = scope;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    if (!(handler instanceof HandlerMethod method)) {
      return;
    }
    Audited audited = method.getMethodAnnotation(Audited.class);
    AuditLog.Outcome outcome =
        switch (response.getStatus()) {
          case 403 -> AuditLog.Outcome.DENIED;
          case 404 -> AuditLog.Outcome.NOT_FOUND;
          default -> null;
        };
    Optional<UUID> tenant = callerTenant();
    if (audited == null || outcome == null || tenant.isEmpty()) {
      // Operators have no tenant to record into; their refusals stay in the request log.
      return;
    }
    AuditAction action = audited.value();
    String targetType = audited.targetType().isEmpty() ? action.targetType() : audited.targetType();
    UUID target = target(request, audited.targetVariable());
    try {
      scope.inTenant(
          tenant.get(), () -> audit.refused(tenant.get(), action, outcome, targetType, target));
    } catch (RuntimeException e) {
      // The response has been sent; losing this event must not turn into a second failure.
      log.error("could not record a refused {} in the audit log", action.code(), e);
    }
  }

  private static Optional<UUID> callerTenant() {
    return switch (SecurityContextHolder.getContext().getAuthentication()) {
      case CallerAuthentication caller -> caller.caller().tenantId();
      case ScimAuthentication scim -> Optional.of(scim.tenantId());
      case null, default -> Optional.empty();
    };
  }

  private static UUID target(HttpServletRequest request, String variable) {
    if (variable.isEmpty()
        || !(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
            instanceof Map<?, ?> variables)
        || !(variables.get(variable) instanceof String value)) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      // Not an ID we could have issued; the request is recorded without one.
      return null;
    }
  }
}
