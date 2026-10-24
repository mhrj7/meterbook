package com.meterbook.identity.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only way to reach tenant data (ADR-0009). Each call runs in a transaction that tells
 * PostgreSQL whose data it may see ({@code app.tenant_id}, or {@code app.scope = platform});
 * row-level security does the rest. The settings are transaction-local, so they never outlive the
 * call on a pooled connection. A nested call restores the outer scope when it returns, so an inner
 * scope cannot widen the rest of the outer transaction.
 */
@Component
public class TenantScope {

  private static final Logger log = LoggerFactory.getLogger(TenantScope.class);

  private final DSLContext db;
  private final TransactionTemplate transaction;

  public TenantScope(DSLContext db, TransactionTemplate transaction) {
    this.db = db;
    this.transaction = transaction;
  }

  /** Runs {@code work} seeing only {@code tenant}'s rows. */
  public <T> T inTenant(UUID tenant, Supplier<T> work) {
    if (tenant == null) {
      throw new IllegalArgumentException("a tenant scope needs a tenant");
    }
    return scoped(tenant.toString(), "", work);
  }

  public void inTenant(UUID tenant, Runnable work) {
    inTenant(
        tenant,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * Runs {@code work} across tenants, on the few tables that allow it. Every use names its reason;
   * keep them few and reviewed (ADR-0009).
   */
  public <T> T asPlatform(String reason, Supplier<T> work) {
    log.debug("platform scope: {}", reason);
    return scoped("", "platform", work);
  }

  private <T> T scoped(String tenant, String scope, Supplier<T> work) {
    return transaction.execute(
        status -> {
          String outerTenant = setting("app.tenant_id");
          String outerScope = setting("app.scope");
          set(tenant, scope);
          T result;
          try {
            result = work.get();
          } catch (RuntimeException e) {
            // A failed statement aborts the transaction, so restoring can fail too; the original
            // error is the one worth reporting, and a rollback discards the settings anyway.
            try {
              set(outerTenant, outerScope);
            } catch (RuntimeException restore) {
              e.addSuppressed(restore);
            }
            throw e;
          }
          set(outerTenant, outerScope);
          return result;
        });
  }

  private String setting(String name) {
    String value =
        db.fetchValue(DSL.field("current_setting({0}, true)", String.class, DSL.val(name)));
    return value == null ? "" : value;
  }

  private void set(String tenant, String scope) {
    db.execute(
        "select set_config('app.tenant_id', {0}, true), set_config('app.scope', {1}, true)",
        tenant, scope);
  }
}
