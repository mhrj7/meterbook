# ADR-0014: Tenant RBAC: permissions per endpoint, roles bound in the identity database, SCIM groups as subjects

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** MB-T11
- **Deciders:** Mohit Raj

## Context

MB-T07 shipped two roles, both Keycloak realm roles carried in the access token:
- `platform-admin`: our operators.
- `tenant-admin`: everything inside a tenant.

Every other member of a tenant could read everything and change nothing, and a customer could not say who may do
what. Enterprise customers expect to grant access through their identity provider's groups, which they already
provision to us over SCIM (ADR-0007).

Two promises are open:
- ADR-0007: "RBAC from SCIM groups (MB-T11)".
- docs/design/identity.md §9: the identity service account holds `manage-users`, which lets it map *any* realm
  role, `platform-admin` included. Restricting that inside Keycloak was left for MB-T11.

Constraints:
- A permission removed must stop working on the next request, not when a token expires.
- A tenant must never be left without an administrator by its own users.
- Granting must not be a path to more privilege than the granter has.
- Authorization must be checkable against the contract: one rule per endpoint, no implicit defaults.

## Options considered

### Option A: tenant roles as Keycloak realm roles in the token (extend today's model)
- Pros: no lookup per request; Keycloak's admin console shows them.
- Cons:
  - Realm roles are global, not per tenant. A user in two tenants would carry both tenants' roles.
  - A change waits for the next token (up to 5 minutes).
  - Mapping SCIM groups means the identity service writes realm role mappings, the exact power we want to remove
    from it.

### Option B: tenant roles bound in the identity database, resolved per request
- Pros:
  - Per tenant by construction: bindings carry `tenant_id` under row-level security (ADR-0009).
  - A change takes effect on the next request.
  - SCIM groups are rows in the same database, so a group binding is a join, not a sync.
  - The identity service no longer needs to map any Keycloak role.
- Cons:
  - One indexed query per authenticated request.
  - Roles are not visible in Keycloak.

### Option C: an external policy engine (OpenFGA, OPA)
- Pros: relationship-based models, shared by future services.
- Cons: another stateful component and a second consistency boundary, for a model that today is four roles over
  one tenant.

## Decision

We will use **Option B**.

- **Permissions:**
  - Every endpoint requires exactly one named permission (for example `projects:create`, `audit_events:read`).
  - It is declared on the handler method and checked by one interceptor before the handler runs.
  - The check happens before any data is read, so a refused call returns 403 whether or not the target exists.
  - A test asserts that every mapped endpoint declares exactly one permission. SCIM endpoints are authorized by
    their SCIM token, and `/v1/api-key` by an API key.
- **Roles are fixed sets of permissions, defined in code:**

| Role | Permissions |
|---|---|
| `viewer` | every `:read` permission except `role_bindings:read` and `audit_events:read` |
| `auditor` | `viewer`, plus `role_bindings:read` and `audit_events:read` |
| `developer` | `viewer`, plus `projects:create`, `projects:update`, `environments:create`, `api_keys:create`, `api_keys:revoke` and `api_keys:rotate` |
| `admin` | every tenant permission: also SSO connections, SCIM tokens, domains and role bindings |

  A test checks the whole matrix: every role against every endpoint.
- **Bindings (`role_bindings`):**
  - Columns: tenant, role, subject.
  - A subject is a user (Keycloak user ID, who must be a member of the tenant) or a SCIM group of the tenant.
  - A caller's roles are the union of their user bindings and the bindings of every SCIM group they are a member of
    (`scim_group_members` joined with `scim_users` on `keycloak_user_id`).
  - Roles are resolved for each request inside the caller's tenant scope, without a cache, so removing a binding or
    a group membership takes effect on the next request.
- **The database is the only source of tenant roles:**
  - The token's `tenant-admin` realm role is no longer read.
  - Creating a tenant writes the initial admin's `admin` binding in the same transaction as the tenant.
  - Tenants created before MB-T11 are backfilled once at startup. Every holder of the `tenant-admin` realm role gets
    an `admin` binding in their organization. The backfill is idempotent and logs what it adds.
  - `platform-admin` stays a realm role. It is about operating Meterbook, not about any tenant.
- **Escalation rules:**
  - Binding needs `role_bindings:create`, which only `admin` has.
  - Nobody can bind a role whose permissions are not all their own.
  - The API refuses to delete a tenant's last direct `admin` user binding (`last_admin`).
  - Deleting a SCIM group removes its bindings. Deprovisioning users or groups through SCIM is the customer's
    identity provider's decision and can leave a tenant without an admin. Recovery is a platform-operator action
    (runbook, follow-up), recorded in the tenant's audit log (ADR-0013).
- **Keycloak no longer lets the service map roles:**
  - The service account loses `manage-users`.
  - The realm enables fine-grained admin permissions (version 2). The service account gets a Users permission with
    `view` and `manage` only, no `map-roles`, through a client policy for that service account.
  - The `organizations` and `identity-providers` roles stay.
  - `Directory.grantRealmRole` is removed.
  - A Testcontainers test against the real realm proves the service account can still do everything the identity
    service does, and gets 403 when it tries to map `platform-admin` or `tenant-admin` to anyone.

## Consequences

- **Positive:**
  - Customers grant access through their own IdP groups.
  - A revoked role stops working on the next request.
  - A compromised identity service cannot make anyone a platform operator through role mappings.
  - Authorization is declared in one place per endpoint and tested against the contract.
- **Negative / accepted trade-offs:**
  - One more indexed query per authenticated request.
  - Roles are not shown in Keycloak's console.
  - `manage-identity-providers` still allows identity-provider mappers that assign roles at sign-in. Keycloak's
    fine-grained permissions have no identity-provider resource type to restrict this. The service never creates
    mappers, and a test asserts the providers it creates have none.
- **Follow-up work:**
  - Operator recovery of a tenant left without an admin (runbook and endpoint).
  - Custom roles defined by tenants.
  - Project-scoped bindings.
  - Alert on identity-provider mappers appearing (MB-T14).

## How we will know it was wrong

- Role resolution adds more than 5 ms to the p99 of `GET /v1/me` (G4: 50 ms), or
- customers need per-project or per-environment grants that fixed tenant-wide roles cannot express, or
- a second service needs the same authorization model.
