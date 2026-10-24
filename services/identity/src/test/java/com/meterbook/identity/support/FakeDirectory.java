package com.meterbook.identity.support;

import com.meterbook.identity.directory.Directory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory {@link Directory} that records calls and can be told to fail at a given step. */
public final class FakeDirectory implements Directory {

  public final Map<UUID, List<String>> organizations = new ConcurrentHashMap<>();
  public final Map<UUID, String> users = new ConcurrentHashMap<>();
  public final Map<UUID, Set<UUID>> members = new ConcurrentHashMap<>();
  public final Map<UUID, Set<String>> roles = new ConcurrentHashMap<>();
  public final Map<String, SamlProvider> providers = new ConcurrentHashMap<>();
  public final Map<String, UUID> providerOrganizations = new ConcurrentHashMap<>();
  public final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
  public final Map<UUID, Integer> logouts = new ConcurrentHashMap<>();
  public final Map<UUID, Map<String, String>> links = new ConcurrentHashMap<>();
  public final Map<UUID, List<String>> profiles = new ConcurrentHashMap<>();
  public final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
  private final Map<String, RuntimeException> failures = new HashMap<>();
  private final Map<String, Runnable> hooks = new HashMap<>();

  public void reset() {
    organizations.clear();
    users.clear();
    members.clear();
    roles.clear();
    providers.clear();
    providerOrganizations.clear();
    enabled.clear();
    logouts.clear();
    links.clear();
    profiles.clear();
    calls.clear();
    failures.clear();
    hooks.clear();
  }

  public void failOn(String method, RuntimeException failure) {
    failures.put(method, failure);
  }

  public void onCall(String method, Runnable hook) {
    hooks.put(method, hook);
  }

  public int calls(String method) {
    AtomicInteger count = calls.get(method);
    return count == null ? 0 : count.get();
  }

  private void enter(String method) {
    calls.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
    Runnable hook = hooks.get(method);
    if (hook != null) {
      hook.run();
    }
    RuntimeException failure = failures.get(method);
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public UUID createOrganization(String name, String alias, List<String> domains) {
    enter("createOrganization");
    UUID id = UUID.randomUUID();
    organizations.put(id, List.copyOf(domains));
    return id;
  }

  @Override
  public void deleteOrganization(UUID organization) {
    enter("deleteOrganization");
    organizations.remove(organization);
    members.values().forEach(m -> m.remove(organization));
  }

  @Override
  public Optional<UUID> findUserByEmail(String email) {
    enter("findUserByEmail");
    return users.entrySet().stream()
        .filter(e -> e.getValue().equals(email))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  @Override
  public UUID createUser(String email, String firstName, String lastName) {
    enter("createUser");
    UUID id = UUID.randomUUID();
    users.put(id, email);
    enabled.put(id, true);
    profiles.put(id, List.of(email, firstName, lastName));
    return id;
  }

  @Override
  public void deleteUser(UUID user) {
    enter("deleteUser");
    users.remove(user);
    members.remove(user);
    roles.remove(user);
  }

  @Override
  public List<UUID> organizationsOf(UUID user) {
    enter("organizationsOf");
    return new ArrayList<>(members.getOrDefault(user, Set.of()));
  }

  @Override
  public void addMember(UUID organization, UUID user) {
    enter("addMember");
    members.computeIfAbsent(user, u -> new HashSet<>()).add(organization);
  }

  @Override
  public void grantRealmRole(UUID user, String role) {
    enter("grantRealmRole");
    roles.computeIfAbsent(user, u -> new HashSet<>()).add(role);
  }

  @Override
  public void createSamlProvider(UUID organization, SamlProvider provider) {
    enter("createSamlProvider");
    providers.put(provider.alias(), provider);
    providerOrganizations.put(provider.alias(), organization);
  }

  @Override
  public void deleteIdentityProvider(String alias) {
    enter("deleteIdentityProvider");
    providers.remove(alias);
    providerOrganizations.remove(alias);
  }

  @Override
  public void updateUser(UUID user, String email, String firstName, String lastName) {
    enter("updateUser");
    users.put(user, email);
    profiles.put(user, List.of(email, firstName, lastName));
  }

  @Override
  public void setEnabled(UUID user, boolean value) {
    enter("setEnabled");
    enabled.put(user, value);
  }

  @Override
  public void logout(UUID user) {
    enter("logout");
    logouts.merge(user, 1, Integer::sum);
  }

  @Override
  public void removeMember(UUID organization, UUID user) {
    enter("removeMember");
    Set<UUID> of = members.get(user);
    if (of != null) {
      of.remove(organization);
    }
  }

  @Override
  public void linkIdentity(UUID user, String providerAlias, String subject) {
    enter("linkIdentity");
    links.computeIfAbsent(user, u -> new ConcurrentHashMap<>()).put(providerAlias, subject);
  }
}
