# Evidence: tenancy (tenant → project → environment) and PostgreSQL row-level security (MB-T08)

- **Date:** 2026-10-24
- **Definition of Done:** "Cross-tenant access test fails closed": `RowLevelSecurityTest` and `ProjectApiTest` on
  every identity change in CI (job **Java identity**), and on RDS through EKS.
- **On real AWS:** [Terraform apply #40079839501](https://github.com/mhrj7/meterbook/actions/runs/40079839501) on
  `main` (`4295d6c`), eu-north-1, dev cluster, identity image `sha256:bac5cde7…` (signed, built from #31).
- **Design:** [ADR-0009](../adr/0009-tenancy-and-row-level-security.md); contract `/v1/projects` in
  `contracts/openapi/identity-v1.yaml`.
- **Account ID and resource IDs are redacted below** (also masked in the public run log).

## Two layers of isolation

1. **Application:** every repository call filters by the caller's tenant and runs inside
   `TenantScope.inTenant(tenant, …)`. Another tenant's project is a 404.
2. **Database:** every table holding tenant data has `ENABLE` and **`FORCE ROW LEVEL SECURITY`**. Policies compare
   `tenant_id` with the transaction-local `app.tenant_id`, so the setting cannot leak to the next request on a pooled
   connection. With no scope set, no rows are visible and inserts are refused. A narrow, logged
   `TenantScope.asPlatform(reason)` reaches only `tenants`, `tenant_domains` and `scim_tokens`, for signup, domain
   checks and SCIM token resolution.

The service and its tests connect as an ordinary role (`NOSUPERUSER NOBYPASSRLS`) that owns its tables. A superuser
ignores RLS, so tests run as one would prove nothing.

## Fails closed (CI, PostgreSQL 17 via Testcontainers)

| Test | Result |
|---|---|
| Guard: every table outside a two-entry allow-list (`flyway_schema_history`, `idempotency_keys`, neither has `tenant_id`) enables **and** forces RLS | pass |
| The service role is neither superuser nor `BYPASSRLS` | pass |
| No scope: `tenants` and `projects` read 0 rows; an insert fails with "violates row-level security policy" | pass |
| Tenant A, **unfiltered** query: sees only A's projects; an unfiltered `UPDATE` touches only A's row; deleting tenant B affects 0 rows | pass |
| Tenant A inserts a project for tenant B → refused by RLS | pass |
| Platform scope sees tenants but **no projects**, and cannot insert one | pass |
| Nested scopes restore the outer tenant | pass |
| API: tenant B lists 0 projects; GET project, list/get environments, rename and add environment on A's project → 404; A's project unchanged | pass |

Total: 176 tests; line coverage 97.4%, branch coverage 87.0% (gates are 90% and 85%).

## Projects and environments

- A new project gets `production` (live) and `sandbox` (test) in the same transaction. `Idempotency-Key` replays
  return the same project.
- Slugs are unique per tenant (another tenant may reuse them) → 409. Limits: 100 projects per tenant, 20
  environments per project → 422. Each check locks the parent row, so concurrent creates cannot overshoot.
- Tenant admins write; members read; operators without a tenant get 403 `tenant_required`.

## On EKS (RDS PostgreSQL)

| Step | Result | Duration |
|---|---|---|
| Apply dev | 107 resources added | 12m36s |
| Verify (`tests/infra/verify-dev.sh`) | **83 passed, 0 failed** | 4m36s |
| Destroy dev | 107 destroyed, 0 left in state | 11m40s |

The five new RDS checks run in-cluster as `identity_dev`, right after an operator creates a tenant through the API:

```
PASS  identity's migrations ran in identity_dev on RDS
PASS  identity's database role is no superuser and cannot bypass row-level security
PASS  every identity table holding tenant data forces row-level security
PASS  without a tenant scope the new tenant is invisible (fails closed)
PASS  without a tenant scope an insert is refused by row-level security
PASS  in the tenant's own scope the tenant is visible
```

### Full verification output

```
PASS  state stored in s3://***/dev/terraform.tfstate
PASS  state bucket versioning enabled
PASS  no stale state lock left behind
PASS  VPC vpc-<id> exists
PASS  VPC flow logs active
PASS  database subnets have dedicated route tables
PASS  database route tables have no internet or NAT route
PASS  EKS cluster ACTIVE
PASS  EKS private endpoint enabled
PASS  EKS public endpoint not open to 0.0.0.0/0
PASS  EKS secrets envelope-encrypted with KMS
PASS  EKS audit logging enabled
PASS  node group default-<id> ACTIVE
PASS  worker nodes running (2)
PASS  worker nodes are x86_64, matching node group AMI AL2023_x86_64_STANDARD
PASS  VPC CNI enforces NetworkPolicies
PASS  pod identity agent add-on ACTIVE
PASS  RDS instance available
PASS  RDS not publicly accessible
PASS  RDS storage encrypted
PASS  RDS master password managed in Secrets Manager
PASS  RDS backups enabled
PASS  RDS enforces TLS (rds.force_ssl=1)
PASS  bucket invoices: all public access blocked
PASS  bucket invoices: versioning enabled
PASS  bucket invoices: plain HTTP denied by policy
PASS  bucket exports: all public access blocked
PASS  bucket exports: versioning enabled
PASS  bucket exports: plain HTTP denied by policy
PASS  secret meterbook-dev/stripe-api-key exists and uses the app KMS key
PASS  secret meterbook-dev/stripe-webhook-signing-secret exists and uses the app KMS key
PASS  secret meterbook-dev/identity-oidc-client-secret exists and uses the app KMS key
PASS  ESO bound to its service account by Pod Identity
PASS  ESO role may read an app secret
PASS  ESO role may not read other secrets
PASS  ESO role may not write secrets
PASS  ArgoCD server and controllers running
PASS  ArgoCD application meterbook-dev-root Synced
PASS  ArgoCD application meterbook-staging-root Synced
PASS  ArgoCD application platform-external-secrets Synced
PASS  ArgoCD application platform-secrets-dev Synced
PASS  ArgoCD application platform-secrets-staging Synced
PASS  ArgoCD application smoke-dev Synced
PASS  ArgoCD application smoke-staging Synced
PASS  ArgoCD application meterbook-dev-root Healthy
PASS  ArgoCD application meterbook-staging-root Healthy
PASS  ArgoCD application platform-external-secrets Healthy
PASS  ArgoCD application smoke-dev Healthy
PASS  ArgoCD application smoke-staging Healthy
PASS  ClusterSecretStore Ready: ESO authenticates to Secrets Manager through Pod Identity
PASS  Secrets Manager value delivered to Kubernetes Secret in meterbook-dev
PASS  Secrets Manager value delivered to Kubernetes Secret in meterbook-staging
PASS  ArgoCD application platform-secrets-dev Healthy
PASS  ArgoCD application platform-secrets-staging Healthy
PASS  a pod reads the AWS-managed secret at runtime
PASS  smoke service 2/2 available in meterbook-dev
PASS  smoke service 2/2 available in meterbook-staging
PASS  smoke pods run as non-root with a read-only root filesystem
PASS  NetworkPolicy: same-namespace traffic to the smoke service is allowed
PASS  NetworkPolicy: traffic from another namespace is blocked
PASS  ArgoCD application identity-dev Synced
PASS  ArgoCD application identity-dev Healthy
PASS  db-bootstrap created the dev databases and roles
PASS  keycloak available in meterbook-dev
PASS  identity available in meterbook-dev
PASS  identity and keycloak pods run as non-root with a read-only root filesystem in meterbook-dev
PASS  ArgoCD application identity-staging Synced
PASS  ArgoCD application identity-staging Healthy
PASS  db-bootstrap created the staging databases and roles
PASS  keycloak available in meterbook-staging
PASS  identity available in meterbook-staging
PASS  identity and keycloak pods run as non-root with a read-only root filesystem in meterbook-staging
PASS  identity rejects a request without a token (401)
PASS  an operator token from Keycloak is accepted; /v1/me needs a tenant (403)
PASS  an operator creates a tenant through identity, which provisions Keycloak (201)
PASS  the new tenant is readable (200)
PASS  identity's migrations ran in identity_dev on RDS
PASS  identity's database role cannot connect to Keycloak's database
PASS  identity's database role is no superuser and cannot bypass row-level security
PASS  every identity table holding tenant data forces row-level security
PASS  without a tenant scope the new tenant is invisible (fails closed)
PASS  without a tenant scope an insert is refused by row-level security
PASS  in the tenant's own scope the tenant is visible
83 passed, 0 failed
```

## What the runs found, and the fixes

| Found by | Problem | Fix |
|---|---|---|
| Switching tests to a non-superuser role | Every fixture inserted without a scope and RLS refused them, which is fail-closed working as intended | Fixtures go through `TenantScope` (#30) |
| Same | Each cached Spring context holds its own pool; an ordinary role cannot use PostgreSQL's reserved connection slots | Test database runs with `max_connections=500` (#30) |
| Schema tests | After a failed statement, restoring the outer scope failed too ("transaction is aborted") and hid the real error | The original error is rethrown, with the restore failure attached as suppressed (#30) |
| EKS runs #40075668235, #40077815299 | Correct results failed their checks: `kubectl run -i` sometimes delivers a pod's output twice, so a key read `0 0`. #33 made failed checks print the value (hosts and IPs redacted), which showed this | Each key is read from its first line (#34) |
