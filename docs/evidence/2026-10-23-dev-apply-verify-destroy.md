# Evidence: dev apply → verify → destroy on AWS (MB-T03, MB-T04)

- **Date:** 2026-10-23
- **Workflow run:** [Terraform apply #39939409530](https://github.com/mhrj7/meterbook/actions/runs/39939409530) on `main`
- **Region:** eu-north-1 · **Environment:** dev (disposable)
- **Account ID and resource IDs are redacted below.**

| Job | Result | Duration |
|---|---|---|
| Apply dev | success | 12m54s (10:29:05 → 10:41:59 UTC) |
| Verify dev on AWS (`tests/infra/verify-dev.sh`) | success, 35 passed / 0 failed | 49s |
| Destroy dev | success, 99 resources destroyed, 0 left in state | 11m49s |

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
35 passed, 0 failed
```

## Issues found and fixed by the real runs

| Run | Failure | Fix |
|---|---|---|
| Bootstrap #1 | `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Trust GitHub's immutable OIDC subjects (PR #7) |
| Dev apply #1 | Node group: Spot `UnfulfillableCapacity` on a new account | On-demand nodes for dev (PR #8) |
| Dev apply #2 | Node group: instance type not eligible on the AWS Free plan; secrets "scheduled for deletion" | `m7i-flex.large` for dev; recovery window 0 for disposable envs and a pre-apply purge (PR #9) |

Each failed run's destroy job removed every resource it had created.
