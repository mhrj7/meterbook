package com.meterbook.identity.authz;

import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks the {@link Requires} permission of an endpoint before it runs (ADR-0014), so a refused
 * call reads nothing and returns 403 whether or not its target exists. Tenant permissions come from
 * the caller's role bindings, resolved for this request; {@code tenants:create} needs a platform
 * operator, and operators may also read any tenant.
 */
public class AuthorizationInterceptor implements HandlerInterceptor {

  private final RoleBindings bindings;

  public AuthorizationInterceptor(RoleBindings bindings) {
    this.bindings = bindings;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    Requires requires = method.getMethodAnnotation(Requires.class);
    if (requires == null) {
      return true;
    }
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof CallerAuthentication authentication)) {
      throw ApiException.forbidden("user_required", "this endpoint needs a user's access token");
    }
    Caller caller = authentication.caller();
    Permission permission = requires.value();
    boolean operator = caller.has(Role.PLATFORM_ADMIN);
    if (permission.isPlatform()) {
      if (!operator) {
        throw ApiException.forbidden(
            "platform_admin_required", "only platform administrators can do this");
      }
      return true;
    }
    if (operator && permission == Permission.TENANTS_READ) {
      return true;
    }
    var tenant =
        caller
            .tenantId()
            .orElseThrow(
                () -> ApiException.forbidden("tenant_required", "this belongs to a tenant"));
    Set<Permission> held = TenantRole.permissionsOf(bindings.rolesOf(tenant, caller.userId()));
    if (!held.contains(permission)) {
      throw ApiException.forbidden(
          "permission_required", "this needs the " + permission.code() + " permission");
    }
    return true;
  }
}
