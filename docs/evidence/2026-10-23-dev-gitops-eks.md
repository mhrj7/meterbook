# Evidence: GitOps on EKS — dev apply → verify → destroy (MB-T06)

- **Date:** 2026-10-23
- **Workflow run:** [Terraform apply #39993278414](https://github.com/mhrj7/meterbook/actions/runs/39993278414) on `main` (`bbcb931`)
- **Region:** eu-north-1 · **Environments:** dev and staging (namespaces on the disposable dev cluster)
- **Account ID and resource IDs are redacted below** (the account ID is also masked in the public run log).

| Step | Result | Duration |
|---|---|---|
| Apply dev (VPC, EKS, RDS, buckets, secrets, ArgoCD and the GitOps roots) | success, 101 resources added | 12m20s (17:13:54 → 17:26:14 UTC) |
| Verify (`tests/infra/verify-dev.sh`: AWS baseline, GitOps, secrets delivery, hardening) | success, 60 passed / 0 failed | 4m05s |
| Destroy dev | success, 101 resources destroyed, 0 left in state | 12m05s |
| Whole run | success | 29m00s |

What this proves on real AWS: Terraform installs ArgoCD and one root per environment; ArgoCD then deploys everything
else from Git (External Secrets Operator, the secret store, the services ApplicationSet); AWS Secrets Manager values
reach pods in both namespaces through Pod Identity; the smoke service runs hardened; the VPC CNI enforces the chart's
NetworkPolicy.

## Verification output

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
60 passed, 0 failed
```

## The same checks on every pull request (kind)

CI job **GitOps end-to-end (kind + ArgoCD)** installs ArgoCD with the clusters' own values
(`infra/argocd/install/argocd-values.yaml`) and syncs dev and staging at the commit under test: 18 passed in 263s on
PR #12 ([CI run 39991279763](https://github.com/mhrj7/meterbook/actions/runs/39991279763)). It adds one check EKS does
not run: scaling staging by hand is reverted by self-heal.

## Issues found and fixed by these runs

| Where | Failure | Fix |
|---|---|---|
| kind e2e | Smoke pods `CrashLoopBackOff`: `exec: "--port=9898": executable file not found` — podinfo has no ENTRYPOINT, so `args` replaced its CMD | Set `command: ["./podinfo"]` with the args (PR #12) |
| CI e2e | `argocd-redis` `ImagePullBackOff`, then `toomanyrequests: Data limit exceeded` from `ecr-public.aws.com` on shared runner addresses | Redis from `mirror.gcr.io` (same official image), pinned by digest, in values shared by Terraform and the test (PR #12) |
| Public logs | Role ARNs and the state bucket name (containing the account ID) printed in Terraform workflow logs after the repository went public | Moved to secrets and `mask-aws-account-id: true` (PR #13); earlier runs that printed it are deleted from Actions by the repository owner |
| Bootstrap | 1 h apply-role sessions too short for apply + verify + destroy | `max_session_duration` 7200 s (bootstrap re-run, 1 change) |
