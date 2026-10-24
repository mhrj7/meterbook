package com.meterbook.identity.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/** The Flyway schema as PostgreSQL 17 sees it, including the constraints tenancy relies on. */
@SpringBootTest
@Import(TestDatabase.class)
class MigrationTest {

  @Autowired DSLContext db;
  @Autowired TenantScope scope;

  /** Schema constraints are checked below RLS, so these tests act with platform scope. */
  private <T> T platform(java.util.function.Supplier<T> work) {
    return scope.asPlatform("schema test", work);
  }

  private UUID tenant(String slug) {
    UUID id = UUID.randomUUID();
    platform(
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, id)
                .set(Tables.TENANTS.SLUG, slug)
                .set(Tables.TENANTS.NAME, slug)
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .execute());
    return id;
  }

  @Test
  void tables_live_in_the_identity_schema() {
    assertThat(
            db.fetch(
                "select table_name from information_schema.tables where table_schema = 'identity' order by 1"))
        .extracting(r -> r.get(0, String.class))
        .contains("tenants", "tenant_domains", "idempotency_keys", "flyway_schema_history");
  }

  @Test
  void a_domain_belongs_to_one_tenant_only() {
    UUID a = tenant("dom-a-" + UUID.randomUUID().toString().substring(0, 8));
    UUID b = tenant("dom-b-" + UUID.randomUUID().toString().substring(0, 8));
    String domain = UUID.randomUUID() + ".test";
    platform(
        () ->
            db.insertInto(Tables.TENANT_DOMAINS)
                .set(Tables.TENANT_DOMAINS.DOMAIN, domain)
                .set(Tables.TENANT_DOMAINS.TENANT_ID, a)
                .execute());

    assertThatThrownBy(
            () ->
                platform(
                    () ->
                        db.insertInto(Tables.TENANT_DOMAINS)
                            .set(Tables.TENANT_DOMAINS.DOMAIN, domain)
                            .set(Tables.TENANT_DOMAINS.TENANT_ID, b)
                            .execute()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void domains_and_slugs_must_be_lower_case() {
    assertThatThrownBy(() -> tenant("Upper")).isInstanceOf(DataIntegrityViolationException.class);
    UUID t = tenant("lower-" + UUID.randomUUID().toString().substring(0, 8));
    assertThatThrownBy(
            () ->
                platform(
                    () ->
                        db.insertInto(Tables.TENANT_DOMAINS)
                            .set(Tables.TENANT_DOMAINS.DOMAIN, "Acme.TEST")
                            .set(Tables.TENANT_DOMAINS.TENANT_ID, t)
                            .execute()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deleting_a_tenant_removes_its_domains() {
    UUID t = tenant("del-" + UUID.randomUUID().toString().substring(0, 8));
    String domain = UUID.randomUUID() + ".test";
    platform(
        () ->
            db.insertInto(Tables.TENANT_DOMAINS)
                .set(Tables.TENANT_DOMAINS.DOMAIN, domain)
                .set(Tables.TENANT_DOMAINS.TENANT_ID, t)
                .execute());

    platform(() -> db.deleteFrom(Tables.TENANTS).where(Tables.TENANTS.ID.eq(t)).execute());

    assertThat(
            platform(
                () ->
                    db.fetchExists(Tables.TENANT_DOMAINS, Tables.TENANT_DOMAINS.DOMAIN.eq(domain))))
        .isFalse();
  }
}
