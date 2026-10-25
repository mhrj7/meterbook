package com.meterbook.identity.authz;

import static com.meterbook.identity.db.Tables.ROLE_BINDINGS;
import static com.meterbook.identity.db.Tables.SCIM_GROUP_MEMBERS;
import static com.meterbook.identity.db.Tables.SCIM_USERS;
import static com.meterbook.identity.db.Tables.TENANTS;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.AuditLog;
import com.meterbook.identity.db.tables.records.RoleBindingsRecord;
import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Who holds which tenant role (ADR-0014): bindings to users and to SCIM groups, the only source of
 * tenant roles. Resolved on every request, without a cache, so a removed binding or group
 * membership stops working at once.
 */
@Service
public class RoleBindings {

  static final int MAX_BINDINGS = 500;
  static final int RETRY_AFTER_SECONDS = 2;

  /** Who a binding is for: a {@code user} (Keycloak user ID) or a SCIM {@code group}. */
  public record Subject(String type, UUID id) {
    static Subject user(UUID id) {
      return new Subject("user", id);
    }

    static Subject group(UUID id) {
      return new Subject("group", id);
    }
  }

  /** A binding as admins see it. */
  public record Binding(UUID id, String role, Subject subject, OffsetDateTime createdAt) {}

  private final DSLContext db;
  private final TenantScope scope;
  private final Directory directory;
  private final AuditLog audit;
  private final Clock clock;

  public RoleBindings(
      DSLContext db, TenantScope scope, Directory directory, AuditLog audit, Clock clock) {
    this.db = db;
    this.scope = scope;
    this.directory = directory;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * The roles a user holds in a tenant: their own bindings and their SCIM groups'. A SCIM user who
   * was deactivated holds none, even before their token expires.
   */
  public Set<TenantRole> rolesOf(UUID tenant, UUID user) {
    return scope.inTenant(
        tenant,
        () -> {
          if (db.fetchExists(
              SCIM_USERS,
              SCIM_USERS
                  .TENANT_ID
                  .eq(tenant)
                  .and(SCIM_USERS.KEYCLOAK_USER_ID.eq(user))
                  .and(SCIM_USERS.ACTIVE.isFalse()))) {
            return Set.of();
          }
          var groups =
              db.select(SCIM_GROUP_MEMBERS.GROUP_ID)
                  .from(SCIM_GROUP_MEMBERS)
                  .join(SCIM_USERS)
                  .on(SCIM_USERS.ID.eq(SCIM_GROUP_MEMBERS.USER_ID))
                  .where(SCIM_GROUP_MEMBERS.TENANT_ID.eq(tenant))
                  .and(SCIM_USERS.KEYCLOAK_USER_ID.eq(user));
          Set<TenantRole> roles = EnumSet.noneOf(TenantRole.class);
          db.selectDistinct(ROLE_BINDINGS.ROLE)
              .from(ROLE_BINDINGS)
              .where(ROLE_BINDINGS.TENANT_ID.eq(tenant))
              .and(ROLE_BINDINGS.USER_ID.eq(user).or(ROLE_BINDINGS.GROUP_ID.in(groups)))
              .fetch(ROLE_BINDINGS.ROLE)
              .forEach(r -> TenantRole.of(r).ifPresent(roles::add));
          return roles;
        });
  }

  public Pages.Page<Binding> list(
      Caller caller, Optional<Subject> subject, Optional<UUID> startingAfter, int limit) {
    UUID tenant = tenantOf(caller);
    Condition where = ROLE_BINDINGS.TENANT_ID.eq(tenant);
    if (subject.isPresent()) {
      where = where.and(subjectIs(subject.get()));
    }
    if (startingAfter.isPresent()) {
      where = where.and(ROLE_BINDINGS.ID.gt(startingAfter.get()));
    }
    Condition page = where;
    List<Binding> rows =
        scope.inTenant(
            tenant,
            () ->
                db.selectFrom(ROLE_BINDINGS)
                    .where(page)
                    .orderBy(ROLE_BINDINGS.ID)
                    .limit(limit + 1)
                    .fetch(RoleBindings::binding));
    return Pages.of(rows, limit, b -> b.id().toString());
  }

  /**
   * Binds a role. The caller must hold every permission of the role (no granting above one's own),
   * and a user must be a member of the tenant.
   */
  public Binding create(Caller caller, TenantRole role, Subject subject) {
    UUID tenant = tenantOf(caller);
    requireWithinOwn(caller, tenant, role);
    if (subject.type().equals("user")) {
      requireMember(tenant, subject.id());
    }
    try {
      return scope.inTenant(
          tenant,
          () -> {
            lockTenant(tenant);
            if (db.fetchCount(ROLE_BINDINGS, ROLE_BINDINGS.TENANT_ID.eq(tenant)) >= MAX_BINDINGS) {
              throw ApiException.invalid(
                  "role_binding_limit_reached",
                  "a tenant can have at most " + MAX_BINDINGS + " role bindings",
                  null);
            }
            Binding binding = insert(tenant, role, subject);
            audit.record(tenant, AuditAction.ROLE_BINDING_CREATED, binding.id(), details(binding));
            return binding;
          });
    } catch (DataIntegrityViolationException e) {
      throw subject.type().equals("group") && !groupExists(tenant, subject.id())
          ? ApiException.invalid("group_not_found", "no such SCIM group", "group_id")
          : ApiException.conflict(
              "role_binding_exists", "this subject already holds this role", null);
    }
  }

  /** Removes a binding; the tenant's last direct admin binding cannot be removed. */
  public void delete(Caller caller, UUID id) {
    UUID tenant = tenantOf(caller);
    scope.inTenant(
        tenant,
        () -> {
          // Serialises removals, so two admins cannot remove each other at the same moment.
          lockTenant(tenant);
          Binding binding =
              db.selectFrom(ROLE_BINDINGS)
                  .where(ROLE_BINDINGS.TENANT_ID.eq(tenant))
                  .and(ROLE_BINDINGS.ID.eq(id))
                  .fetchOptional(RoleBindings::binding)
                  .orElseThrow(
                      () ->
                          ApiException.notFound("role_binding_not_found", "no such role binding"));
          TenantRole role = TenantRole.of(binding.role()).orElseThrow();
          requireWithinOwn(caller, tenant, role);
          if (role == TenantRole.ADMIN
              && binding.subject().type().equals("user")
              && db.fetchCount(
                      ROLE_BINDINGS,
                      ROLE_BINDINGS
                          .TENANT_ID
                          .eq(tenant)
                          .and(ROLE_BINDINGS.ROLE.eq(TenantRole.ADMIN.value()))
                          .and(ROLE_BINDINGS.USER_ID.isNotNull())
                          .and(ROLE_BINDINGS.ID.ne(id)))
                  == 0) {
            throw ApiException.conflict(
                "last_admin",
                "this is the tenant's last admin bound directly to a user; bind another first",
                null);
          }
          db.deleteFrom(ROLE_BINDINGS).where(ROLE_BINDINGS.ID.eq(id)).execute();
          audit.record(tenant, AuditAction.ROLE_BINDING_DELETED, id, details(binding));
        });
  }

  /**
   * Makes {@code user} an admin of a new tenant; call it inside the transaction that creates the
   * tenant.
   */
  public void bindInitialAdmin(UUID tenant, UUID user) {
    Binding binding = insert(tenant, TenantRole.ADMIN, Subject.user(user));
    audit.record(tenant, AuditAction.ROLE_BINDING_CREATED, binding.id(), details(binding));
  }

  /**
   * Makes {@code user} an admin unless they already are, as the system (the backfill); returns
   * whether a binding was added. Call it in the tenant's scope.
   */
  boolean bindAdminAsSystem(UUID tenant, UUID user) {
    UUID id = UUID.randomUUID();
    int added =
        db.insertInto(ROLE_BINDINGS)
            .set(ROLE_BINDINGS.ID, id)
            .set(ROLE_BINDINGS.TENANT_ID, tenant)
            .set(ROLE_BINDINGS.ROLE, TenantRole.ADMIN.value())
            .set(ROLE_BINDINGS.USER_ID, user)
            .set(ROLE_BINDINGS.CREATED_AT, OffsetDateTime.now(clock))
            .onConflictDoNothing()
            .execute();
    if (added == 1) {
      audit.recordAsSystem(
          tenant,
          AuditAction.ROLE_BINDING_CREATED,
          id,
          Map.of(
              "role",
              TenantRole.ADMIN.value(),
              "subject_type",
              "user",
              "subject_id",
              user.toString(),
              "reason",
              "tenant_admin_backfill"));
    }
    return added == 1;
  }

  private Binding insert(UUID tenant, TenantRole role, Subject subject) {
    return db.insertInto(ROLE_BINDINGS)
        .set(ROLE_BINDINGS.ID, UUID.randomUUID())
        .set(ROLE_BINDINGS.TENANT_ID, tenant)
        .set(ROLE_BINDINGS.ROLE, role.value())
        .set(ROLE_BINDINGS.USER_ID, subject.type().equals("user") ? subject.id() : null)
        .set(ROLE_BINDINGS.GROUP_ID, subject.type().equals("group") ? subject.id() : null)
        .set(ROLE_BINDINGS.CREATED_AT, OffsetDateTime.now(clock))
        .returning()
        .fetchOptional(RoleBindings::binding)
        .orElseThrow(() -> new IllegalStateException("INSERT ... RETURNING returned no row"));
  }

  private void requireWithinOwn(Caller caller, UUID tenant, TenantRole role) {
    Set<Permission> own = TenantRole.permissionsOf(rolesOf(tenant, caller.userId()));
    if (!own.containsAll(role.permissions())) {
      throw ApiException.forbidden(
          "role_above_own", "you can only grant or remove roles whose permissions you hold");
    }
  }

  private void requireMember(UUID tenant, UUID user) {
    List<UUID> organizations;
    try {
      organizations = directory.organizationsOf(user);
    } catch (Directory.UnavailableException e) {
      throw ApiException.unavailable(
          "directory_unavailable",
          "the identity provider is unavailable; nothing was changed",
          RETRY_AFTER_SECONDS);
    }
    if (!organizations.contains(tenant)) {
      throw ApiException.invalid(
          "user_not_in_tenant", "the user is not a member of this tenant", "user_id");
    }
  }

  private boolean groupExists(UUID tenant, UUID group) {
    return scope.inTenant(
        tenant,
        () ->
            db.fetchExists(
                com.meterbook.identity.db.Tables.SCIM_GROUPS,
                com.meterbook.identity.db.Tables.SCIM_GROUPS.ID.eq(group)));
  }

  private void lockTenant(UUID tenant) {
    db.selectFrom(TENANTS).where(TENANTS.ID.eq(tenant)).forUpdate().fetchOne();
  }

  private static Condition subjectIs(Subject subject) {
    return subject.type().equals("user")
        ? ROLE_BINDINGS.USER_ID.eq(subject.id())
        : ROLE_BINDINGS.GROUP_ID.eq(subject.id());
  }

  private static Map<String, String> details(Binding binding) {
    return Map.of(
        "role", binding.role(),
        "subject_type", binding.subject().type(),
        "subject_id", binding.subject().id().toString());
  }

  private static Binding binding(RoleBindingsRecord r) {
    Subject subject =
        r.getUserId() != null ? Subject.user(r.getUserId()) : Subject.group(r.getGroupId());
    return new Binding(r.getId(), r.getRole(), subject, r.getCreatedAt());
  }

  private static UUID tenantOf(Caller caller) {
    return caller
        .tenantId()
        .orElseThrow(
            () -> ApiException.forbidden("tenant_required", "role bindings belong to a tenant"));
  }
}
