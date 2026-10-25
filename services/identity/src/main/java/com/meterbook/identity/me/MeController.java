package com.meterbook.identity.me;

import com.meterbook.identity.authz.Permission;
import com.meterbook.identity.authz.RoleBindings;
import com.meterbook.identity.authz.TenantRole;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.web.ApiException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/me}: the authenticated caller (contracts/openapi/identity-v1.yaml). */
@RestController
public class MeController {

  /** Response body, serialised in snake_case. */
  public record Me(
      UUID userId,
      UUID tenantId,
      String email,
      String name,
      List<String> roles,
      List<String> tenantRoles,
      List<String> permissions,
      String loginMethod,
      UUID ssoConnectionId) {
    public Me {
      roles = List.copyOf(roles);
      tenantRoles = List.copyOf(tenantRoles);
      permissions = List.copyOf(permissions);
    }
  }

  private final RoleBindings bindings;

  public MeController(RoleBindings bindings) {
    this.bindings = bindings;
  }

  /** Needs no permission: a user with no roles may still see that they have none. */
  @GetMapping("/v1/me")
  public Me me(CallerAuthentication authentication) {
    Caller caller = authentication.caller();
    UUID tenant =
        caller
            .tenantId()
            .orElseThrow(
                () ->
                    ApiException.forbidden(
                        "tenant_required", "this endpoint describes a tenant's user"));
    Set<TenantRole> tenantRoles = bindings.rolesOf(tenant, caller.userId());
    return new Me(
        caller.userId(),
        tenant,
        caller.email(),
        caller.name(),
        caller.roles().stream().map(Role::claimValue).sorted().toList(),
        tenantRoles.stream().map(TenantRole::value).sorted().toList(),
        TenantRole.permissionsOf(tenantRoles).stream().map(Permission::code).sorted().toList(),
        caller.loginMethod().name().toLowerCase(java.util.Locale.ROOT),
        caller.ssoConnectionId().orElse(null));
  }
}
