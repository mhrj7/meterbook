# ADR-0009: Tenant hierarchy (tenant > project > environment) and PostgreSQL row-level security

- **Status:** Accepted
- **Date:** 2026-10-24
- **Task:** MB-T08
- **Deciders:** Mohit Raj

## Context

Every Meterbook customer is a tenant (ADR-0007). Inside a tenant, customers run several products or business units,
and each needs a live and a test setup so integrations can be tried without touching real billing. Usage events,
prices, invoices and API keys (MB-T09+) all belong to exactly one environment.

Tenant isolation is a security boundary (engineering rule 5). Today it rests on every query carrying
`WHERE tenant_id = ?`. One forgotten condition in any service, now or in five years, leaks another company's data.

## Options considered

### Option A — Application filters only
- Pros: nothing new.
- Cons: a single missed `WHERE` is a cross-tenant leak; reviews and tests are the only defence.

### Option B — Schema or database per tenant
- Pros: strong isolation.
- Cons: thousands of schemas to migrate; connection pools per tenant; expensive at our scale.

### Option C — Application filters plus PostgreSQL row-level security on every tenant table
- Pros: the database refuses rows of other tenants even when the application forgets to ask; one schema, one pool.
- Cons: every database call must run in a transaction that names its tenant; a few platform operations need an
  explicit, audited way around it.

## Decision

We will use **Option C**, with the hierarchy **tenant → project → environment**:

- **Projects** (per tenant) and **environments** (per project, `kind` = `live` or `test`). A new project gets
  `production` (live) and `sandbox` (test). Billing data references an environment from MB-T09 on.
- **Row-level security** on every table with a `tenant_id`: `ENABLE` and **`FORCE`** (the service's role owns its
  tables, and owners bypass RLS unless forced). Policies compare `tenant_id` with `app.tenant_id`, set with
  `set_config(…, true)` at the start of each transaction, so it cannot leak to the next request on a pooled
  connection.
- **Fail closed:** with no tenant set, a query returns nothing and an insert is refused.
- **Platform scope:** a handful of operations must look across tenants: resolving a SCIM token to its tenant,
  checking a domain is unclaimed, creating a tenant, operators reading any tenant. They run in
  `TenantScope.asPlatform("<reason>")`, which sets `app.scope = platform`. Only the tables those operations touch
  accept it, and the reason is logged.
- **Services connect as a role without `BYPASSRLS`** (RDS: the role db-bootstrap creates), and **tests do too**: a
  superuser ignores RLS, so tests on one would prove nothing.
- A guard test fails the build when any table with a `tenant_id` column lacks forced RLS.

## Consequences

- **Positive:** a cross-tenant leak needs two independent bugs; new tables cannot silently skip isolation.
- **Negative / accepted trade-offs:** every repository call runs inside a tenant or platform scope; a forgotten
  scope shows up as "no rows" (fails closed), not as a leak.
- **Follow-up work:** environment-scoped API keys (MB-T09); per-tenant quotas on projects and environments (MB-T12).

## How we will know it was wrong

RLS policies add more than 10% to p99 of the hottest tenant queries, or platform-scope operations grow beyond a
handful (a sign the boundary is in the wrong place).
