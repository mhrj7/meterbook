# ADR-0013: A customer-facing audit log, written in the same transaction as the change

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** MB-T11
- **Deciders:** Mohit Raj

## Context

Enterprise customers ask "who changed this, and when?" about their own tenant, and their security teams ask "did
anyone try to reach our data?". The identity service logs requests, but logs belong to us. They are not per tenant
or durable, and nobody can prove they are complete. The design doc promised that every admin and SCIM change is
recorded with tenant, actor and request ID (docs/design/identity.md §9, Repudiation). ADR-0010 promised the same for
API key changes. MB-T11's definition of done is "every mutating endpoint emits an audit event" (TC-PLT-011).
TC-PLT-002 waits on one more piece: a cross-tenant attempt must show up as an audit event.

Constraints:
- An event must exist exactly when the change happened. A change without its event loses evidence; an event
  without its change is false evidence.
- Tenants see only their own events (ADR-0009).
- Events must not leak secrets or more personal data than the change itself.
- The log must be append-only for the service itself, not only for well-behaved code.

## Options considered

### Option A: write events in the service's own database transaction (an outbox-style table)
- Pros: atomic with the change; no new infrastructure; row-level security isolates tenants exactly as it does
  every other table.
- Cons: the identity database holds the log, so every later service needs its own table or must publish to a
  shared one. Volume is bounded by the identity database.

### Option B: publish events to a message broker and store them in a separate audit service
- Pros: one log for every service; independent scaling.
- Cons: a broker does not exist yet (MB-T13). Without a transactional outbox, "change committed, event lost" is
  possible. That is the failure this log exists to prevent.

### Option C: derive events from request logs
- Pros: nothing to build in the services.
- Cons: a log line records an attempt, not a committed change. It cannot tell a rolled-back write from a durable
  one, and it is not tenant-isolated.

## Decision

We will use **Option A**. The table and API are shaped so they can later be fed from an outbox to a shared audit
service (Option B) without changing what customers see.

- **Table `audit_events`:**
  - Columns: `id`, `tenant_id`, `occurred_at`, `action` (for example `project.updated`), `outcome`, `actor_type`,
    `actor_id`, `target_type`, `target_id`, `request_id`, `details` (a small JSON object).
  - It has no foreign key to `tenants`. Deleting a tenant must never silently delete its evidence.
- **Who the actor is:**
  - `user`: the Keycloak user ID.
  - `scim_token`: the SCIM token ID.
  - `platform_operator`: an operator's user ID. A tenant created by an operator is recorded in the new tenant's own
    log.
  - Events store IDs, never emails, names, IP addresses, secrets or token values. `details` holds only non-secret
    identifiers and the names of changed fields, never values a customer typed.
- **Successful changes** are recorded by the service, inside the same `TenantScope` transaction that writes the
  change.
  - In operations that use several scopes (a pre-check, then Keycloak, then the write), the event goes into the
    final write scope.
  - The actor comes from the security context. The request ID comes from the logging context (`request_id`, set by
    `RequestIdFilter`).
  - An idempotent replay does not reach the service, so it records nothing new: one change, one event.
- **Refused attempts** are recorded by one MVC interceptor after the response is decided, in a fresh transaction
  (the failed one has already rolled back):
  - 403 is recorded as `denied`.
  - 404 is recorded as `not_found`.
  - Both go into the caller's own tenant, with the target ID taken from the request path.
  - A cross-tenant attempt is a `not_found` in the caller's log, never in the other tenant's. The response and the
    event are the same whether or not the target exists, so the log is not an existence oracle.
  - By-ID reads (`GET …/{id}`) are recorded on 403 and 404 only. Successful reads are not recorded; they are
    request logs, not changes.
- **Not recorded**, with reasons:
  - 400, 409 and 422: nothing changed and the request was the caller's own mistake. They stay in request logs and
    metrics.
  - 401: no verified tenant to record it in. It is logged and counted.
  - 5xx: the operation failed on our side. It is logged and alerted.
- **Append-only:**
  - Under `FORCE ROW LEVEL SECURITY` the table has only a `SELECT` and an `INSERT` policy for the caller's tenant.
    Updates and deletes match no policy.
  - A trigger raises on `UPDATE`, `DELETE` and `TRUNCATE` as well, so even the table owner cannot rewrite history.
    Retention is handled later by dropping whole time partitions (follow-up), never by deleting rows.
- **Read API:** `GET /v1/audit-events`.
  - Newest first, filterable by `action` and `target_id`.
  - Cursor-paged on `(occurred_at, id)` with an opaque cursor.
  - Readable with the `audit_events:read` permission (ADR-0014).
- **Completeness is enforced by tests, not by convention:**
  - Every handler mapping must be in the contract and the contract must have nothing more.
  - Every mutating mapping must name its audit action.
  - A parametrized test calls every mutating endpoint and asserts exactly one `success` event with the right
    action, target, actor and request ID.

What each endpoint records, including changes it makes in Keycloak:

| Endpoint | Action | Target | Also changed in Keycloak |
|---|---|---|---|
| `POST /v1/tenants` | `tenant.created` | tenant | organization, initial admin user |
| `POST /v1/sso-connections` | `sso_connection.created` | SSO connection | SAML identity provider |
| `DELETE /v1/sso-connections/{id}` | `sso_connection.deleted` | SSO connection | identity provider removed |
| `POST /v1/projects` | `project.created` | project | — |
| `PATCH /v1/projects/{id}` | `project.updated` | project | — |
| `POST /v1/projects/{id}/environments` | `environment.created` | environment | — |
| `POST …/api-keys` | `api_key.created` | API key | — |
| `DELETE …/api-keys/{id}` | `api_key.revoked` | API key | — |
| `POST …/api-keys/{id}/rotate` | `api_key.rotated` | API key (the new one; the old one in `details`) | — |
| `POST /v1/scim-tokens` | `scim_token.created` | SCIM token | — |
| `DELETE /v1/scim-tokens/{id}` | `scim_token.revoked` | SCIM token | — |
| `POST /scim/v2/Users` | `scim_user.created` | SCIM user | user created or linked, organization membership |
| `PUT /scim/v2/Users/{id}` | `scim_user.replaced` | SCIM user | profile; disabled and signed out if deactivated |
| `PATCH /scim/v2/Users/{id}` | `scim_user.updated` | SCIM user | as for `PUT` |
| `DELETE /scim/v2/Users/{id}` | `scim_user.deleted` | SCIM user | user removed |
| `POST /scim/v2/Groups` | `scim_group.created` | SCIM group | — |
| `PATCH /scim/v2/Groups/{id}` | `scim_group.updated` | SCIM group | — |
| `DELETE /scim/v2/Groups/{id}` | `scim_group.deleted` | SCIM group | — (its role bindings go with it, ADR-0014) |
| `POST /v1/role-bindings` | `role_binding.created` | role binding | — |
| `DELETE /v1/role-bindings/{id}` | `role_binding.deleted` | role binding | — |
| `POST /v1/domains` | `domain.created` | domain | — |
| `POST /v1/domains/{id}/verify` | `domain.verified` | domain | domain added to the organization |
| `DELETE /v1/domains/{id}` | `domain.deleted` | domain | domain removed from the organization |

## Consequences

- **Positive:**
  - Customers get a complete, tenant-isolated record of changes and refused attempts.
  - TC-PLT-002 and TC-PLT-011 have a durable artefact to check.
  - Losing an event while keeping its change is impossible by construction.
- **Negative / accepted trade-offs:**
  - Every write carries one more insert.
  - The log lives in the identity database until a shared audit service exists.
  - A Keycloak change whose database transaction fails is undone, not recorded; the undo itself is only logged.
  - Events from concurrent transactions can commit out of `occurred_at` order, so a client paging the newest events
    can miss one that commits late. It appears on the next read.
- **Follow-up work:**
  - Monthly partitions and a retention period per plan (MB-T16).
  - An outbox to a shared audit service when other services start writing (MB-T13).
  - Export to the customer's SIEM.

## How we will know it was wrong

- Audit inserts add more than 5 ms to the p99 of a mutating request, or
- the table outgrows the identity database's storage plan before partitions exist, or
- a second service needs to record events before an outbox exists.
