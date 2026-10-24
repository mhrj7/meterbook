package com.meterbook.identity.tenants;

import static com.meterbook.identity.db.Tables.TENANTS;
import static com.meterbook.identity.db.Tables.TENANT_DOMAINS;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Tenants and their email domains. */
@Repository
public class TenantRepository {

  /** A stored tenant. */
  public record Tenant(
      UUID id, String name, String slug, List<String> domains, OffsetDateTime createdAt) {
    public Tenant {
      domains = List.copyOf(domains);
    }
  }

  private final DSLContext db;

  public TenantRepository(DSLContext db) {
    this.db = db;
  }

  public void insert(Tenant tenant) {
    db.insertInto(TENANTS)
        .set(TENANTS.ID, tenant.id())
        .set(TENANTS.SLUG, tenant.slug())
        .set(TENANTS.NAME, tenant.name())
        .set(TENANTS.CREATED_AT, tenant.createdAt())
        .execute();
    var insert = db.insertInto(TENANT_DOMAINS, TENANT_DOMAINS.DOMAIN, TENANT_DOMAINS.TENANT_ID);
    for (String domain : tenant.domains()) {
      insert = insert.values(domain, tenant.id());
    }
    insert.execute();
  }

  public Optional<Tenant> find(UUID id) {
    return db.selectFrom(TENANTS)
        .where(TENANTS.ID.eq(id))
        .fetchOptional()
        .map(
            row ->
                new Tenant(
                    row.getId(), row.getName(), row.getSlug(), domainsOf(id), row.getCreatedAt()));
  }

  public boolean slugExists(String slug) {
    return db.fetchExists(TENANTS, TENANTS.SLUG.eq(slug));
  }

  public List<String> claimedDomains(List<String> domains) {
    return db.select(TENANT_DOMAINS.DOMAIN)
        .from(TENANT_DOMAINS)
        .where(TENANT_DOMAINS.DOMAIN.in(domains))
        .orderBy(TENANT_DOMAINS.DOMAIN)
        .fetch(TENANT_DOMAINS.DOMAIN);
  }

  private List<String> domainsOf(UUID tenant) {
    return db.select(TENANT_DOMAINS.DOMAIN)
        .from(TENANT_DOMAINS)
        .where(TENANT_DOMAINS.TENANT_ID.eq(tenant))
        .orderBy(TENANT_DOMAINS.DOMAIN)
        .fetch(TENANT_DOMAINS.DOMAIN);
  }
}
