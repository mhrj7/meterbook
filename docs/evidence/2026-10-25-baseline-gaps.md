# Evidence: closing the gaps in finished platform work (MB-T05 to MB-T10)

- **Date:** 2026-10-25
- **Why:** an audit of the finished tasks found promises not kept and quality targets not measured. Each gap below is
  closed with tests in CI and, where it runs on AWS, on EKS.
- **On real AWS:** [Terraform apply #40117597732](https://github.com/mhrj7/meterbook/actions/runs/40117597732)
  on `main` (`6f80316`): **123 passed, 0 failed**. Its destroy left 3 free resources behind (see the last table);
  the destroy-only run [#40122325623](https://github.com/mhrj7/meterbook/actions/runs/40122325623) removed them.
- **Account ID and resource IDs are redacted below** (also masked in the public run log).

## What was missing, and what closes it

| Gap (task) | Fix | Proof |
|---|---|---|
| Prod had neither identity (MB-T07) nor the mesh (MB-T10) | `envs/prod`: identity, mesh, smoke, image policy, with prod-only values (own database and secrets); promoted (#44, #52) | GitOps e2e for the **prod** cluster on kind in CI, every GitOps change (30+ checks) |
| Design promised "Keycloak: 2 replicas, PDB"; one ran everywhere (MB-T07) | 2 replicas + PDB for Keycloak and identity in prod and dev (dev mirrors prod) | EKS: 2/2 each, one Infinispan cluster view of 2 (JDBC_PING), on different nodes |
| API keys never created on EKS: no signed-in admin (MB-T09) | The tenant's first admin signs in like a browser (authorization code + PKCE, `tests/infra/oidc_login.py`), creates, uses and revokes a key | EKS: key live → refused on the admin API → revoked → 401 within 60 s |
| No mutation testing, though the Coverage sheet sets 75% (MB-T07–T09) | PIT in CI, four parallel shards, each failing below 75% (`mutation.yml`) | [Run 40120018649](https://github.com/mhrj7/meterbook/actions/runs/40120018649): **739/840 killed (88.0%)**; tenancy packages 94.3%; lowest shard 84% |
| No SAST (TC-PLT-005) or linters (TC-PLT-013) (MB-T05) | CodeQL (security-extended) and Semgrep; SpotBugs in `check`; ruff and ShellCheck | 0 high/critical; SpotBugs found 5 real bugs, fixed (#48) |
| Signatures never checked at admission (ADR-0003/0006 promise) | Sigstore policy-controller, deny by default, one policy: our images signed by `container.yml` on `main` (ADR-0012) | kind: unsigned image refused, every pinned image admitted (server-side dry run through the webhook); EKS: unsigned image refused, our workloads run 2/2 |
| No test case for mTLS (MB-T10) | TC-PLT-020 | kind and EKS, as below |
| M-PLT-07 never measured | Trivy 0.76.1 over the four deployed images | **0 CRITICAL**, including unfixed |

## Findings along the way

| Found by | Problem | Fix |
|---|---|---|
| policy-controller on kind | It cannot verify Sigstore bundles ("bundle support for image signatures is not yet implemented", 0.13.1 and 0.15.1), and cosign 3 signs only bundles, so **every image would have been refused** | CI also writes the classic signature; `container.yml` can re-sign on `main`; all images rebuilt and pinned (#51) |
| Same | podinfo is signed with bundles only | The smoke service is podinfo re-published by our pipeline (scanned, SBOM, signed) |
| Same | The defaulting webhook adds fields to each image policy, and the controller rewrites its webhooks, so ArgoCD never showed them in sync | Policies set those fields explicitly; only the controller-owned webhook fields are ignored (#50) |
| Verification on EKS | `grep -q` under `pipefail` makes a matched pipeline fail with SIGPIPE (`seq 1 3000000 \| grep -q '^5$'` → 141) | Checks over large outputs count matches instead (#49) |
| Verification on EKS | ztunnel logs a connection only when it closes, and the API server's pod proxy cannot reach ztunnel's port | mTLS identities read from ztunnel's connection counters, through port-forward (#47, #49) |
| CI mutation run | One PIT run takes longer than the 150-minute job limit | Four shards; a 30 s PIT timeout constant, since every timeout restarts the worker with its Spring contexts (#53) |
| Dev destroy | A network interface leaked by the VPC CNI blocked the subnet and a security group (`DependencyViolation`) | On a failed destroy, detached interfaces in the dev VPC are removed and the destroy retried once; a destroy-only mode (#54). The cleanup run removed one interface and the last 3 resources |
| Local test of the login | Keycloak's login cookies are `Secure`, and in-cluster Keycloak is plain HTTP (the mesh encrypts that hop) | The login client sends them back; documented in the script |

## On EKS (run 40117597732)

| Step | Result |
|---|---|
| Apply dev | 107 resources added (12m56s) |
| Verify (`tests/infra/verify-dev.sh`) | **123 passed, 0 failed** (9m01s) |
| Destroy | 104 destroyed; 3 left (subnet, security group, VPC: not billed), removed by run 40122325623 |

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
PASS  ArgoCD application platform-istio-base Synced
PASS  ArgoCD application platform-istiod Synced
PASS  ArgoCD application platform-istio-cni Synced
PASS  ArgoCD application platform-ztunnel Synced
PASS  ArgoCD application platform-policy-controller Synced
PASS  ArgoCD application platform-policy Synced
PASS  ArgoCD application platform-external-secrets Synced
PASS  ArgoCD application platform-secrets-dev Synced
PASS  ArgoCD application platform-secrets-staging Synced
PASS  ArgoCD application smoke-dev Synced
PASS  ArgoCD application smoke-staging Synced
PASS  ArgoCD application meterbook-dev-root Healthy
PASS  ArgoCD application meterbook-staging-root Healthy
PASS  ArgoCD application platform-istio-base Healthy
PASS  ArgoCD application platform-istiod Healthy
PASS  ArgoCD application platform-istio-cni Healthy
PASS  ArgoCD application platform-ztunnel Healthy
PASS  ArgoCD application platform-policy-controller Healthy
PASS  ArgoCD application platform-policy Healthy
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
PASS  ztunnel ready on all 2 nodes
PASS  istio-cni-node ready on all 2 nodes
PASS  istiod available
PASS  meterbook-dev enrolled in the ambient mesh
PASS  meterbook-staging enrolled in the ambient mesh
PASS  mesh-wide PeerAuthentication is STRICT
PASS  mesh: plaintext from a pod outside the mesh is rejected by strict mTLS, although the NetworkPolicy admits it
PASS  mesh: an in-mesh client reaches smoke
PASS  mesh: ztunnel counts the request as mTLS from the client's SPIFFE identity
PASS  meterbook-dev enrolled in image admission
PASS  meterbook-staging enrolled in image admission
PASS  an unsigned image is refused at admission in meterbook-dev (no matching policy)
PASS  namespaces outside the policy are not affected
PASS  ArgoCD application identity-dev Synced
PASS  ArgoCD application identity-dev Healthy
PASS  db-bootstrap created the dev databases and roles
PASS  keycloak 2/2 available in meterbook-dev
PASS  identity 2/2 available in meterbook-dev
PASS  Keycloak's two replicas form one cluster in meterbook-dev
PASS  Keycloak replicas run on different nodes in meterbook-dev
PASS  identity and keycloak pods run as non-root with a read-only root filesystem in meterbook-dev
PASS  ArgoCD application identity-staging Synced
PASS  ArgoCD application identity-staging Healthy
PASS  db-bootstrap created the staging databases and roles
PASS  keycloak 1/1 available in meterbook-staging
PASS  identity 1/1 available in meterbook-staging
PASS  identity and keycloak pods run as non-root with a read-only root filesystem in meterbook-staging
PASS  identity rejects a request without a token (401)
PASS  an operator token from Keycloak is accepted; /v1/me needs a tenant (403)
PASS  an operator creates a tenant through identity, which provisions Keycloak (201)
PASS  the new tenant is readable (200)
PASS  /v1/api-key without credentials is refused (401)
PASS  /v1/api-key refuses an unknown key (401)
PASS  an API key refusal is marked no-store, so no service caches it
PASS  /v1/api-key refuses a person's access token: only keys verify there (401)
PASS  the tenant's first admin gets a password through identity's Keycloak account (204)
PASS  the tenant admin signs in through Keycloak like a browser (authorization code + PKCE)
PASS  the admin's token names the new tenant
PASS  the admin's API key is live and names its environment's kind
PASS  an API key cannot call the admin API (401)
PASS  the admin revokes the key (204)
PASS  a revoked key is rejected (401; TC-PLT-003)
PASS  a revoked key is rejected within 60 s (took 0 s)
PASS  mesh: identity's calls to Keycloak travel over mTLS as spiffe://cluster.local/ns/meterbook-dev/sa/identity
PASS  identity's migrations ran in identity_dev on RDS (through V6, API keys)
PASS  identity's database role cannot connect to Keycloak's database
PASS  identity's database role is no superuser and cannot bypass row-level security
PASS  every identity table holding tenant data forces row-level security
PASS  without a tenant scope the new tenant is invisible (fails closed)
PASS  without a tenant scope an insert is refused by row-level security
PASS  in the tenant's own scope the tenant is visible
123 passed, 0 failed
```

## Review after the first pass (#56)

| Found by | Problem | Fix |
|---|---|---|
| Reading CodeQL's SARIF | The CodeQL gate looked for rule severities in `tool.driver`, but CodeQL keeps them with the query packs (`tool.extensions`): every result scored 0, so the gate could not fail while **main had 5 open high alerts**. TC-PLT-005 was recorded as passing too early | The gate reads both and fails when it has nothing to judge; run on main's old SARIF it fails with exactly those 5 |
| Those alerts | `java/user-controlled-bypass` (2): the API key and SCIM filters branched on the Authorization header before the database check | `BearerTokens` only extracts; authentication comes only from the lookup |
| Same | `java/spring-disabled-csrf-protection` (3): every chain is stateless with header-only credentials, so CSRF protection protects nothing | Query excluded in `.github/codeql/codeql-config.yml` with that reason; `NoAmbientCredentialsTest` fails if a chain creates a session, sets a cookie or accepts a cookie as a credential |
| Review | Dev's single policy-controller replica had the chart's PDB, which blocks drains | No PDB in dev; prod runs two with it. (Node-group deletion took the same time with it, 8m16s against 8m18s, so it did not cause the ENI leak) |
| Review | Dependabot had no `docker` ecosystem: no base image, including the re-published podinfo, would ever get an update PR | Added for every service |
| Review | Coverage was quoted from a local build | CI now prints it: 97.4% line, 86.8% branch ([run 40143043993](https://github.com/mhrj7/meterbook/actions/runs/40143043993)) |
| Testing the re-sign job | Never run | Re-signing our smoke digest succeeded ([40143752957](https://github.com/mhrj7/meterbook/actions/runs/40143752957)); a digest we did not build was refused ([40143794642](https://github.com/mhrj7/meterbook/actions/runs/40143794642)) |

After #56, code scanning shows **0 open alerts** on main ([CodeQL 40143497435](https://github.com/mhrj7/meterbook/actions/runs/40143497435)).
