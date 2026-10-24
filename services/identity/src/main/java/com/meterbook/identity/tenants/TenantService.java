package com.meterbook.identity.tenants;

import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.web.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates tenants across Keycloak and the database (design doc §7.3): the organization and admin
 * are created in Keycloak first (its ID is the tenant ID), then the rows are written in one
 * transaction in the new tenant's scope. If anything fails after Keycloak changed, the Keycloak
 * changes are undone.
 */
@Service
public class TenantService {

  private static final Logger log = LoggerFactory.getLogger(TenantService.class);
  static final int RETRY_AFTER_SECONDS = 2;

  /** A validated request to create a tenant. */
  public record NewTenant(
      String name, String slug, List<String> domains, String initialAdminEmail) {}

  private final Directory directory;
  private final TenantRepository tenants;
  private final TenantScope scope;
  private final Clock clock;

  public TenantService(
      Directory directory, TenantRepository tenants, TenantScope scope, Clock clock) {
    this.directory = directory;
    this.tenants = tenants;
    this.scope = scope;
    this.clock = clock;
  }

  public TenantRepository.Tenant create(Caller caller, NewTenant request) {
    if (!caller.has(Role.PLATFORM_ADMIN)) {
      throw ApiException.forbidden(
          "platform_admin_required", "only platform administrators create tenants");
    }
    List<String> domains =
        request.domains().stream().map(d -> d.trim().toLowerCase(Locale.ROOT)).distinct().toList();
    String adminEmail = request.initialAdminEmail().trim().toLowerCase(Locale.ROOT);
    String adminDomain = adminEmail.substring(adminEmail.indexOf('@') + 1);
    if (!domains.contains(adminDomain)) {
      throw ApiException.invalid(
          "admin_email_outside_domains",
          "initial_admin_email must belong to one of the tenant's domains",
          "initial_admin_email");
    }
    if (scope.asPlatform(
        "tenant slugs are unique across tenants", () -> tenants.slugExists(request.slug()))) {
      throw ApiException.conflict("slug_taken", "another tenant uses this slug", null);
    }
    List<String> claimed =
        scope.asPlatform(
            "a domain belongs to one tenant only", () -> tenants.claimedDomains(domains));
    if (!claimed.isEmpty()) {
      throw ApiException.conflict(
          "domain_already_claimed",
          "already belongs to another tenant: " + String.join(", ", claimed),
          null);
    }

    UUID organization = null;
    UUID createdUser = null;
    try {
      organization = directory.createOrganization(request.name(), request.slug(), domains);
      Optional<UUID> existing = directory.findUserByEmail(adminEmail);
      UUID admin;
      if (existing.isPresent()) {
        admin = existing.get();
        if (!directory.organizationsOf(admin).isEmpty()) {
          throw ApiException.conflict(
              "admin_belongs_to_another_tenant",
              "the initial admin already belongs to a tenant",
              null);
        }
      } else {
        admin = directory.createUser(adminEmail, "", "");
        createdUser = admin;
      }
      directory.addMember(organization, admin);
      directory.grantRealmRole(admin, Role.TENANT_ADMIN.claimValue());

      TenantRepository.Tenant tenant =
          new TenantRepository.Tenant(
              organization, request.name(), request.slug(), domains, OffsetDateTime.now(clock));
      scope.inTenant(tenant.id(), () -> tenants.insert(tenant));
      return tenant;
    } catch (RuntimeException e) {
      undo(organization, createdUser);
      throw translate(e);
    }
  }

  public TenantRepository.Tenant get(Caller caller, UUID id) {
    Optional<TenantRepository.Tenant> tenant;
    if (caller.has(Role.PLATFORM_ADMIN)) {
      tenant = scope.asPlatform("operators read any tenant", () -> tenants.find(id));
    } else {
      // Row-level security: another tenant's row is invisible here even before the comparison
      // below.
      tenant = caller.tenantId().flatMap(own -> scope.inTenant(own, () -> tenants.find(id)));
    }
    return tenant.orElseThrow(() -> ApiException.notFound("tenant_not_found", "no such tenant"));
  }

  private void undo(UUID organization, UUID createdUser) {
    try {
      if (createdUser != null) {
        directory.deleteUser(createdUser);
      }
      if (organization != null) {
        directory.deleteOrganization(organization);
      }
    } catch (RuntimeException e) {
      // The original failure is what the caller needs; this one needs a human (design doc §7.3).
      log.error(
          "could not undo a partially created tenant (organization {}, user {})",
          organization,
          createdUser,
          e);
    }
  }

  private static RuntimeException translate(RuntimeException e) {
    return switch (e) {
      case ApiException api -> api;
      case Directory.UnavailableException unavailable ->
          ApiException.unavailable(
              "directory_unavailable",
              "the identity provider is unavailable; nothing was changed",
              RETRY_AFTER_SECONDS);
      case Directory.ConflictException conflict ->
          ApiException.conflict(
              "tenant_conflict", "a tenant with this name or domain already exists", null);
      case DataIntegrityViolationException integrity ->
          ApiException.conflict(
              "tenant_conflict", "a tenant with this slug or domain already exists", null);
      default -> e;
    };
  }
}
