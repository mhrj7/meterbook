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

  void grantRealmRole(UUID user, String role);

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
