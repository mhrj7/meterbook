package com.meterbook.identity.authz;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.Audited;
import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/roles} and {@code /v1/role-bindings} (contracts/openapi/identity-v1.yaml). */
@RestController
public class RoleController {

  /** A role and what it allows. */
  public record RoleDescription(String name, List<String> permissions) {
    public RoleDescription {
      permissions = List.copyOf(permissions);
    }
  }

  /** The fixed roles. */
  public record RoleList(List<RoleDescription> data) {
    public RoleList {
      data = List.copyOf(data);
    }
  }

  /** Request body of {@code POST /v1/role-bindings}: exactly one of the two subjects. */
  public record CreateRoleBinding(
      @NotNull @Pattern(regexp = "admin|developer|viewer|auditor") String role,
      UUID userId,
      UUID groupId) {}

  /** A page of bindings. */
  public record RoleBindingList(
      List<RoleBindings.Binding> data, boolean hasMore, String nextCursor) {
    public RoleBindingList {
      data = List.copyOf(data);
    }
  }

  private final RoleBindings bindings;
  private final Idempotency idempotency;

  public RoleController(RoleBindings bindings, Idempotency idempotency) {
    this.bindings = bindings;
    this.idempotency = idempotency;
  }

  @GetMapping("/v1/roles")
  @Requires(Permission.ROLES_READ)
  public RoleList roles() {
    return new RoleList(
        Arrays.stream(TenantRole.values())
            .map(
                r ->
                    new RoleDescription(
                        r.value(), r.permissions().stream().map(Permission::code).toList()))
            .toList());
  }

  @GetMapping("/v1/role-bindings")
  @Requires(Permission.ROLE_BINDINGS_READ)
  public RoleBindingList list(
      CallerAuthentication auth,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "starting_after", required = false) String startingAfter,
      @RequestParam(name = "user_id", required = false) String userId,
      @RequestParam(name = "group_id", required = false) String groupId) {
    if (limit < 1 || limit > 200) {
      throw ApiException.invalid("limit_out_of_range", "limit must be between 1 and 200", "limit");
    }
    if (userId != null && groupId != null) {
      throw ApiException.invalid(
          "one_subject_only", "filter by user_id or by group_id, not both", "group_id");
    }
    Optional<RoleBindings.Subject> subject =
        userId != null
            ? Optional.of(RoleBindings.Subject.user(uuid(userId, "user_id")))
            : Optional.ofNullable(groupId)
                .map(g -> RoleBindings.Subject.group(uuid(g, "group_id")));
    Pages.Page<RoleBindings.Binding> page =
        bindings.list(
            auth.caller(),
            subject,
            Optional.ofNullable(startingAfter).map(c -> uuid(c, "starting_after")),
            limit);
    return new RoleBindingList(page.data(), page.hasMore(), page.nextCursor());
  }

  @PostMapping("/v1/role-bindings")
  @Requires(Permission.ROLE_BINDINGS_CREATE)
  @Audited(value = AuditAction.ROLE_BINDING_CREATED, targetVariable = "")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateRoleBinding body) {
    if ((body.userId() == null) == (body.groupId() == null)) {
      throw ApiException.invalid(
          "one_subject_required", "give exactly one of user_id and group_id", "user_id");
    }
    RoleBindings.Subject subject =
        body.userId() != null
            ? RoleBindings.Subject.user(body.userId())
            : RoleBindings.Subject.group(body.groupId());
    TenantRole role = TenantRole.of(body.role()).orElseThrow();
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/role-bindings",
        body,
        () -> {
          var binding = bindings.create(auth.caller(), role, subject);
          return ResponseEntity.created(URI.create("/v1/role-bindings/" + binding.id()))
              .body(binding);
        });
  }

  @DeleteMapping("/v1/role-bindings/{id}")
  @Requires(Permission.ROLE_BINDINGS_DELETE)
  @Audited(AuditAction.ROLE_BINDING_DELETED)
  public ResponseEntity<Void> delete(CallerAuthentication auth, @PathVariable UUID id) {
    bindings.delete(auth.caller(), id);
    return ResponseEntity.noContent().build();
  }

  private static UUID uuid(String value, String param) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid(param + "_invalid", param + " must be a UUID", param);
    }
  }
}
