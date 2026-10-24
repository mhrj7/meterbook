package com.meterbook.identity.sso;

import com.meterbook.identity.directory.Directory;
import com.meterbook.identity.security.Caller;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import java.net.URI;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tenant's SSO connections. The identity provider is created in Keycloak first, then the row; a
 * failure after Keycloak changed removes the provider again (design doc §7.3).
 */
@Service
public class SsoConnectionService {

  private static final Logger log = LoggerFactory.getLogger(SsoConnectionService.class);
  static final String ALIAS_PREFIX = "sso-";
  static final int RETRY_AFTER_SECONDS = 2;

  /** A validated request for a SAML connection. */
  public record NewSamlConnection(
      String displayName, String idpEntityId, String ssoUrl, String signingCertificate) {}

  /** A connection plus what the tenant configures in their IdP. */
  public record Connection(
      SsoConnectionRepository.SsoConnection stored, ServiceProvider serviceProvider) {}

  /** The service provider (Keycloak) side of a connection. */
  public record ServiceProvider(String entityId, String acsUrl, String metadataUrl) {}

  private final Directory directory;
  private final SsoConnectionRepository connections;
  private final TransactionTemplate transaction;
  private final SsoProperties properties;
  private final Clock clock;

  public SsoConnectionService(
      Directory directory,
      SsoConnectionRepository connections,
      TransactionTemplate transaction,
      SsoProperties properties,
      Clock clock) {
    this.directory = directory;
    this.connections = connections;
    this.transaction = transaction;
    this.properties = properties;
    this.clock = clock;
  }

  public Connection create(Caller caller, NewSamlConnection request) {
    UUID tenant = tenantAdminTenant(caller);
    String certificate = SigningCertificate.normalise(request.signingCertificate(), clock);
    requireAllowedUrl(request.ssoUrl());
    if (connections.existsFor(tenant)) {
      throw ApiException.conflict(
          "sso_connection_exists", "this tenant already has an SSO connection", null);
    }
    UUID id = UUID.randomUUID();
    String alias = ALIAS_PREFIX + id;
    ServiceProvider serviceProvider = serviceProvider(alias, id);
    SsoConnectionRepository.SsoConnection stored =
        new SsoConnectionRepository.SsoConnection(
            id,
            tenant,
            alias,
            request.displayName(),
            request.idpEntityId(),
            request.ssoUrl(),
            OffsetDateTime.now(clock));
    boolean providerCreated = false;
    try {
      directory.createSamlProvider(
          tenant,
          new Directory.SamlProvider(
              alias,
              request.displayName(),
              serviceProvider.entityId(),
              request.idpEntityId(),
              request.ssoUrl(),
              certificate));
      providerCreated = true;
      transaction.executeWithoutResult(status -> connections.insert(stored));
      return new Connection(stored, serviceProvider);
    } catch (RuntimeException e) {
      // The provider may exist even if the create call failed half-way; deleting a missing one is
      // harmless.
      if (providerCreated || e instanceof Directory.UnavailableException) {
        undo(alias);
      }
      throw translate(e);
    }
  }

  public Connection get(Caller caller, UUID id) {
    UUID tenant = tenantAdminTenant(caller);
    return connections
        .find(tenant, id)
        .map(c -> new Connection(c, serviceProvider(c.keycloakAlias(), c.id())))
        .orElseThrow(
            () -> ApiException.notFound("sso_connection_not_found", "no such SSO connection"));
  }

  public Pages.Page<Connection> list(Caller caller, Optional<UUID> startingAfter, int limit) {
    UUID tenant = tenantAdminTenant(caller);
    List<Connection> rows =
        connections.list(tenant, startingAfter, limit + 1).stream()
            .map(c -> new Connection(c, serviceProvider(c.keycloakAlias(), c.id())))
            .toList();
    return Pages.of(rows, limit, c -> c.stored().id().toString());
  }

  public void delete(Caller caller, UUID id) {
    UUID tenant = tenantAdminTenant(caller);
    SsoConnectionRepository.SsoConnection connection =
        connections
            .find(tenant, id)
            .orElseThrow(
                () -> ApiException.notFound("sso_connection_not_found", "no such SSO connection"));
    try {
      // Keycloak first: once the provider is gone nobody can sign in through it, whatever happens
      // next.
      directory.deleteIdentityProvider(connection.keycloakAlias());
    } catch (Directory.UnavailableException e) {
      throw unavailable();
    }
    connections.delete(tenant, id);
  }

  private ServiceProvider serviceProvider(String alias, UUID id) {
    String issuer = properties.publicIssuer();
    String endpoint = issuer + "/broker/" + alias + "/endpoint";
    return new ServiceProvider(issuer + "/sso/" + id, endpoint, endpoint + "/descriptor");
  }

  void requireAllowedUrl(String url) {
    String scheme = URI.create(url).getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !(scheme.equals("http") && properties.allowInsecureIdpUrls())) {
      throw ApiException.invalid(
          "sso_url_not_https", "the IdP's SSO URL must use https", "saml.sso_url");
    }
  }

  private static UUID tenantAdminTenant(Caller caller) {
    if (!caller.has(Role.TENANT_ADMIN)) {
      throw ApiException.forbidden(
          "tenant_admin_required", "only tenant administrators manage SSO connections");
    }
    return caller
        .tenantId()
        .orElseThrow(
            () -> ApiException.forbidden("tenant_required", "SSO connections belong to a tenant"));
  }

  private void undo(String alias) {
    try {
      directory.deleteIdentityProvider(alias);
    } catch (RuntimeException e) {
      log.error("could not remove identity provider {} after a failed create", alias, e);
    }
  }

  private static ApiException unavailable() {
    return ApiException.unavailable(
        "directory_unavailable",
        "the identity provider is unavailable; nothing was changed",
        RETRY_AFTER_SECONDS);
  }

  private static RuntimeException translate(RuntimeException e) {
    return switch (e) {
      case ApiException api -> api;
      case Directory.UnavailableException unavailable -> unavailable();
      case Directory.ConflictException conflict ->
          ApiException.conflict(
              "sso_connection_exists", "this tenant already has an SSO connection", null);
      case DataIntegrityViolationException integrity ->
          ApiException.conflict(
              "sso_connection_exists", "this tenant already has an SSO connection", null);
      default -> e;
    };
  }
}
