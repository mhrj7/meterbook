# Evidence: service mesh mTLS with Istio ambient (MB-T10)

- **Date:** 2026-10-24
- **Definition of Done:** "Plaintext pod-to-pod traffic rejected". A pod outside the mesh is refused by strict mTLS
  when it calls a meshed service, even from the same namespace, where the NetworkPolicy admits it. ztunnel's log names
  the policy that refused it. Meshed traffic carries verified SPIFFE identities.
- **CI:** GitOps end-to-end on kind, [run 40089193779](https://github.com/mhrj7/meterbook/actions/runs/40089193779)
  (34 passed), on every GitOps change.
- **On real AWS:** [Terraform apply #40091917398](https://github.com/mhrj7/meterbook/actions/runs/40091917398) on
  `main` (`adb0697`), eu-north-1, dev cluster: **107 passed, 0 failed**.
- **Design:** [ADR-0011](../adr/0011-service-mesh-mtls.md): Istio 1.31.3 in ambient mode (no sidecars).
- **Account ID and resource IDs are redacted below** (also masked in the public run log).

## How it is built

| Piece | What it does |
|---|---|
| `platform-istio-base`, `platform-istiod`, `platform-istio-cni`, `platform-ztunnel` (ArgoCD, `envs/dev/mesh.yaml`) | Istio 1.31.3 charts from `ghcr.io/istio/release/charts` (OCI). Images come from `mirror.gcr.io/istio`, because from 1.31 Istio publishes only to Docker Hub, which throttles anonymous CI pulls. Resources are set explicitly for small nodes. The webhook fields istiod rewrites are ignored, so ArgoCD does not fight it |
| `platform-mesh` chart | Mesh-wide `PeerAuthentication` STRICT in `istio-system`. Namespaces `meterbook-dev` and `meterbook-staging` are labelled `istio.io/dataplane-mode: ambient` from Git, applied server-side with `Delete=false`, so a prune never deletes them |
| `meterbook-lib` NetworkPolicy | Also admits HBONE (15008) from the same peers as the service port, and kubelet probes from the mesh's link-local `169.254.7.127/32`; nothing else |

## Proof that the mesh, and not something else, refuses plaintext

A control experiment on the e2e kind cluster. The client sits in the **same namespace** as the service, so the
NetworkPolicy admits it, but it is labelled `istio.io/dataplane-mode=none` (outside the mesh):

| PeerAuthentication | Plaintext client (outside the mesh) | In-mesh client |
|---|---|---|
| PERMISSIVE | reached the service | — |
| STRICT | **refused** | reached the service over HBONE (port 15008) |

ztunnel's log for the refused connection (kind run):

```
error  access  connection complete  src.workload="ctl-strict" src.namespace="meterbook-dev" dst.workload="smoke-…"
       direction="inbound" bytes_sent=0 bytes_recv=0
       error="connection closed due to policy rejection: explicitly denied by: istio-system/istio_converted_static_strict"
```

and for the in-mesh client:

```
info   access  connection complete  src.workload="ctl-mesh" src.identity="spiffe://cluster.local/ns/meterbook-dev/sa/default"
       dst.addr=…:15008 dst.hbone_addr=…:9898 dst.workload="smoke-…"
```

The e2e and the EKS verification now require that exact policy rejection in ztunnel's log, so an unrelated failure
(DNS, an image pull, a timeout) cannot pass as "plaintext rejected".

## On EKS

| Step | Result | Duration |
|---|---|---|
| Apply dev | 107 resources added | 14m02s |
| Verify (`tests/infra/verify-dev.sh`) | **107 passed, 0 failed** | 5m45s |
| Destroy dev | 107 destroyed, 0 left in state | 12m29s |

The mesh checks. The last one shows a real service's traffic, identity calling Keycloak, carrying identity's own
workload identity:

```
PASS  ArgoCD application platform-istio-base Synced
PASS  ArgoCD application platform-istiod Synced
PASS  ArgoCD application platform-istio-cni Synced
PASS  ArgoCD application platform-ztunnel Synced
PASS  ArgoCD application platform-mesh Synced
PASS  ArgoCD application platform-istio-base Healthy
PASS  ArgoCD application platform-istiod Healthy
PASS  ArgoCD application platform-istio-cni Healthy
PASS  ArgoCD application platform-ztunnel Healthy
PASS  ArgoCD application platform-mesh Healthy
PASS  ztunnel ready on all 2 nodes
PASS  istio-cni-node ready on all 2 nodes
PASS  istiod available
PASS  meterbook-dev enrolled in the ambient mesh
PASS  meterbook-staging enrolled in the ambient mesh
PASS  mesh-wide PeerAuthentication is STRICT
PASS  mesh: plaintext from a pod outside the mesh is rejected by strict mTLS, although the NetworkPolicy admits it
PASS  mesh: an in-mesh client reaches smoke
PASS  mesh: ztunnel shows the request arriving over mTLS with the client's SPIFFE identity
PASS  mesh: identity's calls to Keycloak travel over mTLS as spiffe://cluster.local/ns/meterbook-dev/sa/identity
```

The mesh did not break anything that was there before: identity reaches RDS through ztunnel (migrations, row-level
security checks), ESO reaches Secrets Manager, and NetworkPolicy still blocks other namespaces.

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
PASS  ArgoCD application platform-istio-base Synced
PASS  ArgoCD application platform-istiod Synced
PASS  ArgoCD application platform-istio-cni Synced
PASS  ArgoCD application platform-ztunnel Synced
PASS  ArgoCD application platform-mesh Synced
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
PASS  ArgoCD application platform-mesh Healthy
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
PASS  mesh: ztunnel shows the request arriving over mTLS with the client's SPIFFE identity
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
PASS  /v1/api-key without credentials is refused (401)
PASS  /v1/api-key refuses an unknown key (401)
PASS  an API key refusal is marked no-store, so no service caches it
PASS  /v1/api-key refuses a person's access token: only keys verify there (401)
PASS  mesh: identity's calls to Keycloak travel over mTLS as spiffe://cluster.local/ns/meterbook-dev/sa/identity
PASS  identity's migrations ran in identity_dev on RDS (through V6, API keys)
PASS  identity's database role cannot connect to Keycloak's database
PASS  identity's database role is no superuser and cannot bypass row-level security
PASS  every identity table holding tenant data forces row-level security
PASS  without a tenant scope the new tenant is invisible (fails closed)
PASS  without a tenant scope an insert is refused by row-level security
PASS  in the tenant's own scope the tenant is visible
107 passed, 0 failed
```

## What the runs found, and the fixes

| Found by | Problem | Fix |
|---|---|---|
| Choosing a release | Istio 1.31 no longer publishes images or charts to `gcr.io/istio-release`. The usual paths had no 1.31 tags at all | Charts from `ghcr.io/istio/release/charts` (OCI, registered in the ArgoCD values); images from `mirror.gcr.io/istio` (#40) |
| Review of the NetworkPolicy template | In ambient mode, traffic arrives on 15008 and kubelet probes come from `169.254.7.127`, which the existing policies would have dropped | Both admitted; render tests assert nothing else is (#40) |
| First EKS run (#40089628588: 101 passed, 6 failed) | The RDS checks got no output. Identity itself reached RDS through the mesh in that run, so the bug was in the verification: `kubectl run -i` attaches to a short-lived pod and can lose its output (or, as seen in MB-T08, deliver it twice) | In-cluster scripts run to completion and their log is read once; a failed pod reports its state, last lines and events, with addresses redacted (#41). The next run passed 107/107 |
