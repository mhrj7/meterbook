package com.meterbook.identity.scim;

import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.sso.SsoConnectionRepository;
import com.meterbook.identity.tenancy.TenantScope;
import com.meterbook.identity.tenants.TenantRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SCIM users, kept in step with Keycloak (design doc §7.3). Every change to Keycloak happens before
 * the SCIM response, so a deactivated user is signed out by the time the client hears back.
 */
@Service
public class ScimUsers {

  private static final Logger log = LoggerFactory.getLogger(ScimUsers.class);

  private final ScimUserRepository users;
  private final TenantRepository tenants;
  private final SsoConnectionRepository connections;
  private final Directory directory;
  private final TenantScope scope;
  private final Clock clock;

  ScimUsers(
      ScimUserRepository users,
      TenantRepository tenants,
      SsoConnectionRepository connections,
      Directory directory,
      TenantScope scope,
      Clock clock) {
    this.users = users;
    this.tenants = tenants;
    this.connections = connections;
    this.directory = directory;
    this.scope = scope;
    this.clock = clock;
  }

  ScimUser create(UUID tenant, ScimUser.Attributes attributes) {
    requireTenantDomain(tenant, attributes.email());
    if (scope.inTenant(tenant, () -> users.userNameExists(tenant, attributes.userNameKey()))) {
      throw ScimException.uniqueness("userName " + attributes.userName() + " already exists");
    }
    UUID keycloakUser = null;
    boolean created = false;
    boolean addedMember = false;
    try {
      Optional<UUID> existing = directory.findUserByEmail(attributes.email());
      if (existing.isPresent()) {
        keycloakUser = existing.get();
        List<UUID> organizations = directory.organizationsOf(keycloakUser);
        if (organizations.stream().anyMatch(o -> !o.equals(tenant))) {
          throw ScimException.uniqueness("a user with this email belongs to another tenant");
        }
        if (!organizations.contains(tenant)) {
          directory.addMember(tenant, keycloakUser);
          addedMember = true;
        }
        directory.updateUser(
            keycloakUser,
            attributes.email(),
            orEmpty(attributes.givenName()),
            orEmpty(attributes.familyName()));
      } else {
        keycloakUser =
            directory.createUser(
                attributes.email(),
                orEmpty(attributes.givenName()),
                orEmpty(attributes.familyName()));
        created = true;
        directory.addMember(tenant, keycloakUser);
        addedMember = true;
      }
      directory.setEnabled(keycloakUser, attributes.active());
      UUID linked = keycloakUser;
      scope
          .inTenant(tenant, () -> connections.forTenant(tenant))
          .ifPresent(c -> directory.linkIdentity(linked, c.keycloakAlias(), attributes.email()));

      OffsetDateTime now = OffsetDateTime.now(clock);
      ScimUser user = new ScimUser(UUID.randomUUID(), keycloakUser, attributes, 1, now, now);
      scope.inTenant(tenant, () -> users.insert(tenant, user));
      return user;
    } catch (RuntimeException e) {
      undoCreate(tenant, keycloakUser, created, addedMember);
      throw e;
    }
  }

  ScimUser get(UUID tenant, UUID id) {
    return scope
        .inTenant(tenant, () -> users.find(tenant, id))
        .orElseThrow(() -> ScimException.notFound("no such user"));
  }

  Map.Entry<Integer, List<ScimUser>> list(
      UUID tenant, Optional<ScimFilter> filter, int startIndex, int count) {
    return scope.inTenant(tenant, () -> users.list(tenant, filter, startIndex, count));
  }

  List<Map.Entry<UUID, String>> groupsOf(UUID tenant, ScimUser user) {
    return scope.inTenant(tenant, () -> users.groupsOf(user.id()));
  }

  ScimUser replace(UUID tenant, UUID id, ScimUser.Attributes attributes, String ifMatch) {
    return change(tenant, get(tenant, id), attributes, ifMatch);
  }

  ScimUser patch(UUID tenant, UUID id, List<ScimPatch.Operation> operations, String ifMatch) {
    ScimUser current = get(tenant, id);
    return change(
        tenant, current, ScimPatch.applyToUser(current.attributes(), operations), ifMatch);
  }

  void delete(UUID tenant, UUID id) {
    ScimUser user = get(tenant, id);
    // Keycloak first: the user's sessions and links end even if removing the row fails.
    directory.deleteUser(user.keycloakUserId());
    scope.inTenant(tenant, () -> users.delete(tenant, id));
  }

  /** Links every SCIM user of the tenant to a new SSO connection. */
  public void linkAll(UUID tenant, String providerAlias) {
    for (ScimUser user : scope.inTenant(tenant, () -> users.all(tenant))) {
      directory.linkIdentity(user.keycloakUserId(), providerAlias, user.attributes().email());
    }
  }

  private ScimUser change(UUID tenant, ScimUser current, ScimUser.Attributes next, String ifMatch) {
    if (ifMatch != null && !ifMatch.equals(current.etag()) && !ifMatch.equals("*")) {
      throw ScimException.preconditionFailed();
    }
    ScimUser.Attributes before = current.attributes();
    boolean emailChanged = !before.email().equals(next.email());
    if (emailChanged) {
      requireTenantDomain(tenant, next.email());
      Optional<UUID> holder = directory.findUserByEmail(next.email());
      if (holder.isPresent() && !holder.get().equals(current.keycloakUserId())) {
        throw ScimException.uniqueness("another user has this email");
      }
    }
    if (!before.userNameKey().equals(next.userNameKey())
        && scope.inTenant(tenant, () -> users.userNameExists(tenant, next.userNameKey()))) {
      throw ScimException.uniqueness("userName " + next.userName() + " already exists");
    }
    ScimUser updated =
        new ScimUser(
            current.id(),
            current.keycloakUserId(),
            next,
            current.version() + 1,
            current.created(),
            OffsetDateTime.now(clock));
    scope.inTenant(
        tenant,
        () -> {
          if (!users.update(tenant, updated, current.version())) {
            throw ScimException.preconditionFailed();
          }
          // Inside the transaction: if Keycloak fails, the row is not changed either.
          UUID kc = current.keycloakUserId();
          if (emailChanged
              || !Objects.equals(before.givenName(), next.givenName())
              || !Objects.equals(before.familyName(), next.familyName())) {
            directory.updateUser(
                kc, next.email(), orEmpty(next.givenName()), orEmpty(next.familyName()));
          }
          if (before.active() != next.active()) {
            directory.setEnabled(kc, next.active());
            if (!next.active()) {
              directory.logout(kc);
            }
          }
          if (emailChanged) {
            connections
                .forTenant(tenant)
                .ifPresent(c -> directory.linkIdentity(kc, c.keycloakAlias(), next.email()));
          }
        });
    return updated;
  }

  private void requireTenantDomain(UUID tenant, String email) {
    int at = email.lastIndexOf('@');
    if (at < 1 || at == email.length() - 1) {
      throw ScimException.invalidValue("the user needs an email address (userName or emails)");
    }
    String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
    List<String> domains =
        scope
            .inTenant(tenant, () -> tenants.find(tenant))
            .map(TenantRepository.Tenant::domains)
            .orElse(List.of());
    if (!domains.contains(domain)) {
      // Anything else would let one tenant occupy another company's addresses.
      throw ScimException.invalidValue("the email must be in one of the tenant's domains");
    }
  }

  private void undoCreate(UUID tenant, UUID keycloakUser, boolean created, boolean addedMember) {
    if (keycloakUser == null) {
      return;
    }
    try {
      if (created) {
        directory.deleteUser(keycloakUser);
      } else if (addedMember) {
        directory.removeMember(tenant, keycloakUser);
      }
    } catch (RuntimeException e) {
      log.error(
          "could not undo a partially provisioned SCIM user (Keycloak user {})", keycloakUser, e);
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
