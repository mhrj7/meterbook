# ADR-0004: Secrets in AWS Secrets Manager, delivered to pods by External Secrets Operator with EKS Pod Identity

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T04
- **Deciders:** Mohit Raj

## Context

Meterbook services need credentials at runtime: the database password, the payment provider's API key and webhook
signing secret, OIDC client secrets. These must never be in git, in container images, or in Terraform state; they
must be rotatable without a redeploy; and every read must be attributable. The database password is already
generated and rotated by RDS in Secrets Manager (ADR-0002).

## Options considered

### Option A — AWS Secrets Manager + External Secrets Operator (ESO) + EKS Pod Identity
- Pros: managed, encrypted with our KMS key, every read in CloudTrail; RDS writes its password there natively;
  ESO syncs into ordinary Kubernetes Secrets, so services need no AWS SDK; Pod Identity gives ESO a role without
  static keys.
- Cons: synced values exist as Kubernetes Secrets (mitigated by etcd envelope encryption with KMS and RBAC).

### Option B — HashiCorp Vault
- Pros: dynamic secrets, rich policies, cloud-neutral.
- Cons: a stateful, highly available system we would have to run, unseal and back up; overkill for the secret
  count we have.

### Option C — Secrets Store CSI driver (mount from Secrets Manager)
- Pros: values never become Kubernetes Secrets.
- Cons: files only (env vars need sync anyway); a mount failure blocks pod start; less portable.

### Option D — SOPS-encrypted secrets in git
- Pros: simple, GitOps-native.
- Cons: secrets live in git history forever; rotation means commits; key management moves to developers.

## Decision

We will use **Option A**:

- Terraform creates each application secret as an **empty shell** `meterbook-<env>/<name>`, encrypted with a
  dedicated, rotating KMS key. Values are set out-of-band by an operator (`docs/runbooks/secrets.md`), so they never
  pass through Terraform state or CI logs.
- ESO runs in namespace `external-secrets` under a role bound by **EKS Pod Identity**. Its IAM policy lists the
  exact secret ARNs it may read (no wildcards) and allows `kms:Decrypt` only via Secrets Manager.
- One `ClusterSecretStore` (Helm chart `infra/helm/platform-secrets`) is restricted to the application namespaces;
  `ExternalSecret`s map remote secrets to Kubernetes Secrets and refresh hourly, so rotations reach pods without a
  deploy.
- Gitleaks on every CI run keeps secrets out of git.

## Consequences

- **Positive:** no secret in git, images or state; least-privilege, auditable reads; rotation without redeploys;
  services stay cloud-agnostic (they read env vars or files).
- **Negative / accepted trade-offs:** values are present as Kubernetes Secrets in the namespace; access to them is
  controlled by RBAC and etcd encryption. A refresh interval means a rotated value can take up to an hour to arrive
  unless a sync is forced.
- **Follow-up work:** MB-T06 (install ESO and this chart through ArgoCD; wire the RDS secret ARN), MB-T07+ (services
  consume the synced secrets).

## How we will know it was wrong

We need per-request dynamic credentials (e.g. short-lived database users per service), or the number of secrets
and policies outgrows what IAM and ESO manage comfortably. Then revisit Vault.
