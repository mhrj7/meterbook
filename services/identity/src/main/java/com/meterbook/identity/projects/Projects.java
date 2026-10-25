package com.meterbook.identity.projects;

import static com.meterbook.identity.db.Tables.ENVIRONMENTS;
import static com.meterbook.identity.db.Tables.PROJECTS;
import static com.meterbook.identity.db.Tables.TENANTS;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.AuditLog;
import com.meterbook.identity.db.tables.records.EnvironmentsRecord;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * A tenant's projects and their environments (ADR-0009). Every query filters by tenant and runs in
 * that tenant's scope, so isolation holds in the application and again in the database.
 */
@Service
public class Projects {

  static final int MAX_PROJECTS = 100;
  static final int MAX_ENVIRONMENTS = 20;

  /** Where usage and billing data lives; {@code live} handles real money, {@code test} never. */
  public record Environment(
      UUID id, UUID projectId, String name, String slug, String kind, OffsetDateTime createdAt) {}

  /** A project with its environments. */
  public record Project(
      UUID id, String name, String slug, List<Environment> environments, OffsetDateTime createdAt) {
    public Project {
      environments = List.copyOf(environments);
    }
  }

  private final DSLContext db;
  private final TenantScope scope;
  private final AuditLog audit;
  private final Clock clock;

  public Projects(DSLContext db, TenantScope scope, AuditLog audit, Clock clock) {
    this.db = db;
    this.scope = scope;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Creates a project with a {@code production} (live) and a {@code sandbox} (test) environment.
   */
  public Project create(Caller caller, String name, String slug) {
    UUID tenant = adminTenant(caller);
    try {
      return scope.inTenant(
          tenant,
          () -> {
            // Serialises creations per tenant, so concurrent requests cannot overshoot the limit.
            db.selectFrom(TENANTS).where(TENANTS.ID.eq(tenant)).forUpdate().fetchOne();
            if (db.fetchCount(PROJECTS, PROJECTS.TENANT_ID.eq(tenant)) >= MAX_PROJECTS) {
              throw ApiException.invalid(
                  "project_limit_reached",
                  "a tenant can have at most " + MAX_PROJECTS + " projects",
                  null);
            }
            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(clock);
            db.insertInto(PROJECTS)
                .set(PROJECTS.ID, id)
                .set(PROJECTS.TENANT_ID, tenant)
                .set(PROJECTS.SLUG, slug)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.CREATED_AT, now)
                .execute();
            insertEnvironment(tenant, id, "Production", "production", "live");
            insertEnvironment(tenant, id, "Sandbox", "sandbox", "test");
            audit.record(tenant, AuditAction.PROJECT_CREATED, id);
            return find(tenant, id);
          });
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("project_slug_taken", "a project with this slug exists", null);
    }
  }

  public Pages.Page<Project> list(Caller caller, Optional<UUID> startingAfter, int limit) {
    UUID tenant = memberTenant(caller);
    Condition where =
        startingAfter.map(PROJECTS.ID::gt).orElse(PROJECTS.ID.isNotNull()).and(tenantIs(tenant));
    List<Project> rows =
        scope.inTenant(
            tenant,
            () -> {
              var projects =
                  db.selectFrom(PROJECTS)
                      .where(where)
                      .orderBy(PROJECTS.ID)
                      .limit(limit + 1)
                      .fetch();
              var environments = environmentsOf(tenant, projects.map(p -> p.getId()));
              return projects.map(
                  p ->
                      new Project(
                          p.getId(),
                          p.getName(),
                          p.getSlug(),
                          environments.getOrDefault(p.getId(), List.of()),
                          p.getCreatedAt()));
            });
    return Pages.of(rows, limit, p -> p.id().toString());
  }

  public Project get(Caller caller, UUID id) {
    UUID tenant = memberTenant(caller);
    return scope.inTenant(tenant, () -> find(tenant, id));
  }

  public Project rename(Caller caller, UUID id, String name) {
    UUID tenant = adminTenant(caller);
    return scope.inTenant(
        tenant,
        () -> {
          int updated =
              db.update(PROJECTS)
                  .set(PROJECTS.NAME, name)
                  .where(PROJECTS.ID.eq(id))
                  .and(tenantIs(tenant))
                  .execute();
          if (updated == 0) {
            throw projectNotFound();
          }
          audit.record(tenant, AuditAction.PROJECT_UPDATED, id, Map.of("changed", "name"));
          return find(tenant, id);
        });
  }

  public Environment createEnvironment(
      Caller caller, UUID projectId, String name, String slug, String kind) {
    UUID tenant = adminTenant(caller);
    try {
      return scope.inTenant(
          tenant,
          () -> {
            // Locking the project serialises additions to it, like create() does per tenant.
            if (db.selectFrom(PROJECTS)
                .where(PROJECTS.ID.eq(projectId))
                .and(tenantIs(tenant))
                .forUpdate()
                .fetchOptional()
                .isEmpty()) {
              throw projectNotFound();
            }
            if (db.fetchCount(ENVIRONMENTS, ENVIRONMENTS.PROJECT_ID.eq(projectId))
                >= MAX_ENVIRONMENTS) {
              throw ApiException.invalid(
                  "environment_limit_reached",
                  "a project can have at most " + MAX_ENVIRONMENTS + " environments",
                  null);
            }
            Environment environment =
                environment(insertEnvironment(tenant, projectId, name, slug, kind));
            audit.record(
                tenant,
                AuditAction.ENVIRONMENT_CREATED,
                environment.id(),
                Map.of("project_id", projectId.toString()));
            return environment;
          });
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict(
          "environment_slug_taken", "this project has an environment with this slug", null);
    }
  }

  public Pages.Page<Environment> listEnvironments(
      Caller caller, UUID projectId, Optional<UUID> startingAfter, int limit) {
    UUID tenant = memberTenant(caller);
    Condition where =
        startingAfter
            .map(ENVIRONMENTS.ID::gt)
            .orElse(ENVIRONMENTS.ID.isNotNull())
            .and(ENVIRONMENTS.PROJECT_ID.eq(projectId))
            .and(ENVIRONMENTS.TENANT_ID.eq(tenant));
    List<Environment> rows =
        scope.inTenant(
            tenant,
            () -> {
              requireProject(tenant, projectId);
              return db.selectFrom(ENVIRONMENTS)
                  .where(where)
                  .orderBy(ENVIRONMENTS.ID)
                  .limit(limit + 1)
                  .fetch(Projects::environment);
            });
    return Pages.of(rows, limit, e -> e.id().toString());
  }

  public Environment getEnvironment(Caller caller, UUID projectId, UUID id) {
    UUID tenant = memberTenant(caller);
    return scope.inTenant(
        tenant,
        () ->
            db.selectFrom(ENVIRONMENTS)
                .where(ENVIRONMENTS.ID.eq(id))
                .and(ENVIRONMENTS.PROJECT_ID.eq(projectId))
                .and(ENVIRONMENTS.TENANT_ID.eq(tenant))
                .fetchOptional(Projects::environment)
                .orElseThrow(
                    () -> ApiException.notFound("environment_not_found", "no such environment")));
  }

  private EnvironmentsRecord insertEnvironment(
      UUID tenant, UUID projectId, String name, String slug, String kind) {
    return db.insertInto(ENVIRONMENTS)
        .set(ENVIRONMENTS.ID, UUID.randomUUID())
        .set(ENVIRONMENTS.TENANT_ID, tenant)
        .set(ENVIRONMENTS.PROJECT_ID, projectId)
        .set(ENVIRONMENTS.SLUG, slug)
        .set(ENVIRONMENTS.NAME, name)
        .set(ENVIRONMENTS.KIND, kind)
        .set(ENVIRONMENTS.CREATED_AT, OffsetDateTime.now(clock))
        .returning()
        .fetchOne();
  }

  private Project find(UUID tenant, UUID id) {
    var p =
        db.selectFrom(PROJECTS)
            .where(PROJECTS.ID.eq(id))
            .and(tenantIs(tenant))
            .fetchOptional()
            .orElseThrow(Projects::projectNotFound);
    return new Project(
        p.getId(),
        p.getName(),
        p.getSlug(),
        environmentsOf(tenant, List.of(id)).getOrDefault(id, List.of()),
        p.getCreatedAt());
  }

  private void requireProject(UUID tenant, UUID id) {
    if (!db.fetchExists(PROJECTS, PROJECTS.ID.eq(id).and(tenantIs(tenant)))) {
      throw projectNotFound();
    }
  }

  /** Environments by project, oldest first. */
  private Map<UUID, List<Environment>> environmentsOf(UUID tenant, Collection<UUID> projects) {
    return db
        .selectFrom(ENVIRONMENTS)
        .where(ENVIRONMENTS.PROJECT_ID.in(projects))
        .and(ENVIRONMENTS.TENANT_ID.eq(tenant))
        .orderBy(ENVIRONMENTS.CREATED_AT, ENVIRONMENTS.SLUG)
        .fetch(Projects::environment)
        .stream()
        .collect(Collectors.groupingBy(Environment::projectId));
  }

  private static Environment environment(EnvironmentsRecord r) {
    return new Environment(
        r.getId(), r.getProjectId(), r.getName(), r.getSlug(), r.getKind(), r.getCreatedAt());
  }

  private static Condition tenantIs(UUID tenant) {
    return PROJECTS.TENANT_ID.eq(tenant);
  }

  private static ApiException projectNotFound() {
    return ApiException.notFound("project_not_found", "no such project");
  }

  private static UUID adminTenant(Caller caller) {
    UUID tenant = memberTenant(caller);
    if (!caller.has(Role.TENANT_ADMIN)) {
      throw ApiException.forbidden(
          "tenant_admin_required", "only tenant administrators manage projects");
    }
    return tenant;
  }

  private static UUID memberTenant(Caller caller) {
    return caller
        .tenantId()
        .orElseThrow(
            () -> ApiException.forbidden("tenant_required", "projects belong to a tenant"));
  }
}
