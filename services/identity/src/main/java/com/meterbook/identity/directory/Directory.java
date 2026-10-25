package com.meterbook.identity.directory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where users, organizations (tenants) and identity providers live: Keycloak (ADR-0007). The
 * identity service never talks to Keycloak except through this interface.
 */
public interface Directory {

  /** Creates an organization (a tenant) with its email domains; returns its ID. */
  UUID createOrganization(String name, String alias, List<String> domains);

  void deleteOrganization(UUID organization);

  Optional<UUID> findUserByEmail(String email);

  /** Creates an enabled user whose username is their email address; returns the user ID. */
  UUID createUser(String email, String firstName, String lastName);

  void deleteUser(UUID user);

  /** The organizations the user is a member of. */
  List<UUID> organizationsOf(UUID user);

  void addMember(UUID organization, UUID user);

  /** The users who are members of the organization. */
  List<UUID> membersOf(UUID organization);

  /**
   * The user's realm roles, read-only: the service never maps roles (ADR-0014); this is how the
   * pre-MB-T11 {@code tenant-admin} holders are found once.
   */
  java.util.Set<String> realmRolesOf(UUID user);

  void updateUser(UUID user, String email, String firstName, String lastName);

  /**
   * Enables or disables sign-in; disabling does not end existing sessions (see {@link #logout}).
   */
  void setEnabled(UUID user, boolean enabled);

  /** Ends every session of the user, so refresh tokens stop working immediately. */
  void logout(UUID user);

  void removeMember(UUID organization, UUID user);

  /**
   * Links the user to an identity provider: a sign-in there whose subject is {@code subject} is
   * this user. Replaces an existing link to the same provider.
   */
  void linkIdentity(UUID user, String providerAlias, String subject);

  /**
   * A tenant's SAML 2.0 identity provider as Keycloak brokers it.
   *
   * @param serviceProviderEntityId the entity ID Keycloak presents to the IdP for this connection
   * @param signingCertificate base64 DER of the IdP's signing certificate
   */
  record SamlProvider(
      String alias,
      String displayName,
      String serviceProviderEntityId,
      String idpEntityId,
      String ssoUrl,
      String signingCertificate) {}

  /**
   * Creates the identity provider and routes every email domain of the organization to it.
   * Assertions must be signed by {@code signingCertificate}. A first sign-in creates a new user and
   * never links to an existing account (design doc §9).
   */
  void createSamlProvider(UUID organization, SamlProvider provider);

  void deleteIdentityProvider(String alias);

  /** The directory could not be reached or failed; nothing can be assumed about the change. */
  final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The change conflicts with existing data (a name, alias, domain or email already in use). */
  final class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
      super(message);
    }
  }
}
