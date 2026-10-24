package com.meterbook.identity.tenancy;

import static com.meterbook.identity.db.Tables.PROJECTS;
import static com.meterbook.identity.db.Tables.TENANTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterbook.identity.support.TestDatabase;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/**
 * The database layer of tenant isolation (ADR-0009): it holds even when a query forgets its {@code
 * tenant_id} filter, and it fails closed when no scope is set.
 */
@SpringBootTest
@Import(TestDatabase.class)
class RowLevelSecurityTest {

  /** Tables that hold no tenant data; every other table must force row-level security. */
  private static final List<String> UNSCOPED = List.of("flyway_schema_history", "idempotency_keys");

  @Autowired DSLContext db;
  @Autowired TenantScope scope;

  private UUID a;
  private UUID b;

  @BeforeEach
  void tenants() {
    a = tenantWithProject();
    b = tenantWithProject();
  }

  private UUID tenantWithProject() {
    UUID id = UUID.randomUUID();
    scope.inTenant(
        id,
        () -> {
          db.insertInto(TENANTS)
              .set(TENANTS.ID, id)
              .set(TENANTS.SLUG, "rls-" + id.toString().substring(0, 8))
              .set(TENANTS.NAME, "Tenant")
              .set(TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
              .execute();
          insertProject(id);
        });
    return id;
  }

  private int insertProject(UUID tenant) {
    UUID id = UUID.randomUUID();
    return db.insertInto(PROJECTS)
        .set(PROJECTS.ID, id)
        .set(PROJECTS.TENANT_ID, tenant)
        .set(PROJECTS.SLUG, "p-" + id.toString().substring(0, 8))
        .set(PROJECTS.NAME, "Project")
        .set(PROJECTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute();
  }

  /** Every project the current scope can see, with no tenant filter at all. */
  private List<UUID> visibleProjectTenants() {
    return db.selectDistinct(PROJECTS.TENANT_ID).from(PROJECTS).fetch(PROJECTS.TENANT_ID);
  }

  @Test
  void every_table_except_the_allow_list_forces_row_level_security() {
    var tables =
        db.fetch(
            """
            select c.relname, c.relrowsecurity, c.relforcerowsecurity
              from pg_class c join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'identity' and c.relkind = 'r'
            """);

    assertThat(tables).isNotEmpty();
    for (var table : tables) {
      String name = table.get(0, String.class);
      if (UNSCOPED.contains(name)) {
        continue;
      }
      assertThat(table.get(1, Boolean.class)).as("%s enables RLS", name).isTrue();
      assertThat(table.get(2, Boolean.class)).as("%s forces RLS", name).isTrue();
    }
  }

  @Test
  void allow_listed_tables_hold_no_tenant_column() {
    assertThat(
            db.fetch(
                """
                select table_name from information_schema.columns
                 where table_schema = 'identity' and column_name = 'tenant_id'
                """))
        .extracting(r -> r.get(0, String.class))
        .doesNotContainAnyElementsOf(UNSCOPED);
  }

  @Test
  void the_service_role_cannot_bypass_row_level_security() {
    var role =
        db.fetchOne("select rolsuper, rolbypassrls from pg_roles where rolname = current_user");

    assertThat(role.get(0, Boolean.class)).isFalse();
    assertThat(role.get(1, Boolean.class)).isFalse();
  }

  @Test
  void without_a_scope_nothing_is_visible_and_nothing_can_be_written() {
    assertThat(db.fetchCount(TENANTS)).isZero();
    assertThat(db.fetchCount(PROJECTS)).isZero();
    assertThatThrownBy(() -> insertProject(a))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("violates row-level security policy");
  }

  @Test
  void a_tenant_sees_and_changes_only_its_own_rows_even_without_a_filter() {
    assertThat(scope.inTenant(a, this::visibleProjectTenants)).containsExactly(a);
    assertThat(scope.inTenant(a, () -> db.fetchCount(TENANTS))).isEqualTo(1);

    int renamed = scope.inTenant(a, () -> db.update(PROJECTS).set(PROJECTS.NAME, "x").execute());
    int deleted = scope.inTenant(a, () -> db.deleteFrom(TENANTS).where(TENANTS.ID.eq(b)).execute());

    assertThat(renamed).isEqualTo(1);
    assertThat(deleted).isZero();
    assertThat(scope.inTenant(b, () -> db.fetchValue(PROJECTS.NAME))).isEqualTo("Project");
  }

  @Test
  void a_tenant_cannot_write_rows_for_another_tenant() {
    assertThatThrownBy(() -> scope.inTenant(a, () -> insertProject(b)))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("violates row-level security policy");
  }

  @Test
  void the_platform_scope_reaches_only_the_platform_tables() {
    assertThat(scope.asPlatform("test", () -> db.fetchCount(TENANTS, TENANTS.ID.in(a, b))))
        .isEqualTo(2);
    assertThat(scope.asPlatform("test", this::visibleProjectTenants)).isEmpty();
    assertThatThrownBy(() -> scope.asPlatform("test", () -> insertProject(a)))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("violates row-level security policy");
  }

  @Test
  void a_nested_scope_ends_where_it_was_opened() {
    var seen =
        scope.inTenant(
            a,
            () -> {
              var inner = scope.inTenant(b, this::visibleProjectTenants);
              return List.of(inner, visibleProjectTenants());
            });

    assertThat(seen.get(0)).containsExactly(b);
    assertThat(seen.get(1)).containsExactly(a);
  }

  @Test
  void a_scope_needs_a_tenant() {
    assertThatThrownBy(() -> scope.inTenant(null, () -> 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
