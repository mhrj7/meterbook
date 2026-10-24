# Evidence: identity — SSO end-to-end and Keycloak + identity on EKS (MB-T07)

- **Date:** 2026-10-24
- **Definition of Done (TC-PLT-001):** "SSO e2e test passes with a test IdP" — CI job **SSO end-to-end** on every
  identity change, e.g. [CI run 40065295770](https://github.com/mhrj7/meterbook/actions/runs/40065295770).
- **On real AWS:** [Terraform apply #40065555690](https://github.com/mhrj7/meterbook/actions/runs/40065555690) on
  `main` (`68d46f2`), eu-north-1, dev cluster (dev and staging namespaces).
- **Account ID and resource IDs are redacted below** (also masked in the public run log).

## SSO end-to-end (browser)

Playwright drives Chromium through a real SAML 2.0 sign-in. Everything is set up through Meterbook's own APIs; the
customer IdP is a second Keycloak realm.

| Step | Result |
|---|---|
| Operator creates tenant Acme; Acme's admin connects their SAML IdP, issues a SCIM token; the SCIM client provisions Jane | pass |
| Jane enters her email, is routed by domain to Acme's IdP, signs in; `/v1/me` shows tenant Acme, `login_method: sso`, the connection | pass |
| Bob, who exists only in Acme's IdP, signs in just in time and lands in tenant Acme | pass |
| **Attack:** tenant Globex's IdP asserts `jane@acme.test` → refused ("User with email … already exists"), no session | pass |
| SCIM `active=false` → Jane's refresh token rejected, next sign-in stops at "Account is disabled" | pass |

## On EKS

| Step | Result | Duration |
|---|---|---|
| Apply dev | 107 resources added | 12m47s |
| Verify (`tests/infra/verify-dev.sh`, `bash -eo pipefail`) | **78 passed, 0 failed** | 4m40s |
| Destroy dev | 107 destroyed, 0 left in state | 11m49s |

### Verification output

```
PASS  state stored in s3://meterbook-tfstate-<account-id>/dev/terraform.tfstate
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
78 passed, 0 failed
```

## What the tests found, and the fixes

| Found by | Problem | Fix |
|---|---|---|
| Design review while writing SCIM | First SSO sign-in **linked to any existing account with the same email**: a tenant controls its own IdP, so it could sign in as another tenant's user (cross-tenant account takeover) | Never link by email; SCIM links its users to their own tenant's IdP explicitly; SCIM accepts only emails in the tenant's domains. The e2e attack step guards it (#21, #23, #24) |
| SCIM token review | A replayable token response would have stored the SCIM secret in plain text in `idempotency_keys` for 24 h | Responses with secrets are not stored; a repeat is 409 `idempotency_key_used` (#22) |
| SSO e2e | Keycloak 26.8 routes by **domain** (`identityProviderAlias` + `autoRedirect`), not by IdP config; users got a password form | Adapter routes every tenant domain to its IdP (#24) |
| SSO e2e | Just-in-time SSO users were created but not added to the tenant (no tenant in their token) | First-sign-in flow includes Organization Member Onboard (#24) |
| Server-side dry run | Digest-only images produced the label `version: sha256:…`, rejected by Kubernetes | Label uses the first 12 hex characters (#25) |
| db-bootstrap test on PostgreSQL 17 | PostgreSQL 16+ needs `SET` on a role to hand it a database; `REVOKE … FROM PUBLIC` as non-owner revoked nothing (other services could connect) | Grant `SET` without `INHERIT`; revoke as the owner; isolation asserted on RDS (#25) |
| First EKS run (#40057010528) | ArgoCD could not render the identity apps (`no template meterbook-lib.validate`): a subchart's library is not packaged from a clean checkout | Chart declares the library; render tests use a clean copy; GitOps e2e fails on any `ComparisonError` (#27) |
| First EKS run | **Verification failed but the run was green**: job-level `defaults.run` dropped `shell: bash`, so `pipefail` was off | Six jobs fixed; CI guard `scripts/check_workflow_shells.py` (#27) |
