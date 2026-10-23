package com.meterbook.identity.sso;

import static com.meterbook.identity.db.Tables.SSO_CONNECTIONS;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** SSO connections. Every method is scoped to one tenant. */
@Repository
public class SsoConnectionRepository {

  /** A stored connection. */
  public record SsoConnection(
      UUID id,
      UUID tenantId,
      String keycloakAlias,
      String displayName,
      String idpEntityId,
      String ssoUrl,
      OffsetDateTime createdAt) {}

  private final DSLContext db;

  public SsoConnectionRepository(DSLContext db) {
    this.db = db;
  }

  public void insert(SsoConnection connection) {
    db.insertInto(SSO_CONNECTIONS)
        .set(SSO_CONNECTIONS.ID, connection.id())
        .set(SSO_CONNECTIONS.TENANT_ID, connection.tenantId())
        .set(SSO_CONNECTIONS.KEYCLOAK_ALIAS, connection.keycloakAlias())
        .set(SSO_CONNECTIONS.PROTOCOL, "saml")
        .set(SSO_CONNECTIONS.DISPLAY_NAME, connection.displayName())
        .set(SSO_CONNECTIONS.IDP_ENTITY_ID, connection.idpEntityId())
        .set(SSO_CONNECTIONS.SSO_URL, connection.ssoUrl())
        .set(SSO_CONNECTIONS.CREATED_AT, connection.createdAt())
        .execute();
  }

  public Optional<SsoConnection> find(UUID tenant, UUID id) {
    return db.selectFrom(SSO_CONNECTIONS)
        .where(SSO_CONNECTIONS.TENANT_ID.eq(tenant))
        .and(SSO_CONNECTIONS.ID.eq(id))
        .fetchOptional()
        .map(SsoConnectionRepository::map);
  }

  public boolean existsFor(UUID tenant) {
    return db.fetchExists(SSO_CONNECTIONS, SSO_CONNECTIONS.TENANT_ID.eq(tenant));
  }

  /** A page ordered by ID, after {@code startingAfter} when given. */
  public List<SsoConnection> list(UUID tenant, Optional<UUID> startingAfter, int limit) {
    var condition = SSO_CONNECTIONS.TENANT_ID.eq(tenant);
    if (startingAfter.isPresent()) {
      condition = condition.and(SSO_CONNECTIONS.ID.gt(startingAfter.get()));
    }
    return db.selectFrom(SSO_CONNECTIONS)
        .where(condition)
        .orderBy(SSO_CONNECTIONS.ID)
        .limit(limit)
        .fetch(SsoConnectionRepository::map);
  }

  public boolean delete(UUID tenant, UUID id) {
    return db.deleteFrom(SSO_CONNECTIONS)
            .where(SSO_CONNECTIONS.TENANT_ID.eq(tenant))
            .and(SSO_CONNECTIONS.ID.eq(id))
            .execute()
        == 1;
  }

  private static SsoConnection map(
      com.meterbook.identity.db.tables.records.SsoConnectionsRecord r) {
    return new SsoConnection(
        r.getId(),
        r.getTenantId(),
        r.getKeycloakAlias(),
        r.getDisplayName(),
        r.getIdpEntityId(),
        r.getSsoUrl(),
        r.getCreatedAt());
  }
}
