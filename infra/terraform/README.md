# Meterbook infrastructure (Terraform)

AWS infrastructure for one Meterbook environment: network, Kubernetes, PostgreSQL, S3, and the IAM roles CI uses
to deploy. Decisions are recorded in [ADR-0002](../../docs/adr/0002-infrastructure-as-code.md).

## Layout

```
bootstrap/          account level: state bucket, GitHub OIDC provider, CI plan/apply roles (run by CI)
envs/dev/           dev root module   — disposable, cheap
envs/prod/          prod root module  — highly available, protected
modules/stack/      one complete environment; holds the dev-vs-prod guard rails
modules/network/    VPC: public / private / database / intra subnets, NAT, flow logs  (wraps terraform-aws-modules/vpc)
modules/cluster/    EKS, Graviton managed nodes, KMS-encrypted secrets, audit logs     (wraps terraform-aws-modules/eks)
modules/postgres/   RDS PostgreSQL: private, encrypted, TLS-only, RDS-managed password
modules/bucket/     S3: private, versioned, encrypted, TLS 1.2+ only
modules/github-oidc/ GitHub Actions -> AWS roles without static keys (used by bootstrap)
modules/app-secrets/ app secret shells, KMS key, and the External Secrets Operator Pod Identity role (ADR-0004)
```

## What each environment gets

| | dev | prod |
|---|---|---|
| Availability zones | 2 | 3 |
| NAT gateways | 1 shared | 1 per AZ |
| Kubernetes API | public, limited to `admin_cidrs` | private only |
| Nodes | 1–3 × m7i-flex.large (x86, Free-plan eligible), on-demand | 3–9 × m7g.large (Graviton), on-demand |
| PostgreSQL | db.t4g.micro, single-AZ, 1-day PITR | db.m7g.large, **multi-AZ**, 14-day PITR, Performance Insights |
| Deletion protection / final snapshot | off (disposable) | on |
| Log retention | 7 days | 90 days |

The stack module refuses unsafe prod settings (single-AZ DB, < 14 days of backups, shared NAT, 2 AZs, disposable).

## Security defaults (enforced in modules and covered by tests)

- Database: never public; ingress only from the EKS node security group; `rds.force_ssl = 1`; storage encrypted;
  master password generated and stored by RDS in Secrets Manager, so it never touches Terraform state.
- Buckets: all public access blocked, ACLs disabled, versioning on, SSE, deny non-TLS and TLS < 1.2.
- Kubernetes: private endpoint by default; `0.0.0.0/0` is rejected for the public endpoint; control-plane audit logs.
- CI: GitHub OIDC only. Pull requests can assume the read-only plan role; only jobs in the protected GitHub
  environment (`dev`, `production`) can assume the apply role.
- Network: VPC flow logs; default security group locked down; databases and control plane in subnets with no
  internet route.

### Accepted risks

| Finding | Where | Why accepted | Follow-up |
|---|---|---|---|
| AWS-0040 public EKS endpoint | dev only | Developer access without a VPN; CIDR allow-list enforced | — |
| AWS-0104 node egress to 0.0.0.0/0 | nodes | Image pulls and AWS APIs via NAT | VPC endpoints + egress filtering for prod |

Each is suppressed inline with a `#trivy:ignore` comment next to its justification.

## Cost estimate (on-demand list prices, before data transfer; eu-north-1 is similar or slightly cheaper)

Rough figures for planning; confirm with the AWS Pricing Calculator before applying.

| Item | dev / month | prod / month |
|---|---|---|
| EKS control plane | ~$73 | ~$73 |
| Nodes | ~$140 (2 × m7i-flex.large) | ~$180 (3 × m7g.large) |
| NAT gateways | ~$33 | ~$99 |
| RDS (+ storage) | ~$15 | ~$250 (multi-AZ) |
| Logs, KMS, EBS, S3 | ~$10 | ~$40 |
| **Total** | **~$270** | **~$640** |

**dev is ephemeral:** the apply workflow destroys it after verification by default, so a verification run costs roughly $1–3.

## First-time setup (once per AWS account)

Decisions in [ADR-0005](../../docs/adr/0005-account-level-ci-roles-and-ephemeral-dev.md). Nobody runs Terraform
from a laptop; every apply runs in GitHub Actions through OIDC roles.

1. **Seed CI access:** in AWS CloudShell, as an administrator, run
   `bash scripts/cloudshell-bootstrap.sh <owner>@<owner-id>/meterbook@<repo-id>` (GitHub's immutable OIDC subject;
   `gh api repos/<owner>/meterbook/actions/oidc/customization/sub` prints it). It creates the GitHub OIDC provider and the
   `meterbook-terraform-apply` role, trusted only by this repository's `dev` and `production` GitHub environments.
2. **Repository settings:** GitHub environments `dev` and `production`, each limited to the `main` branch, and these
   repository variables:

   | Variable | Value |
   |---|---|
   | `AWS_REGION` | `eu-north-1` |
   | `TF_STATE_BUCKET` | `meterbook-tfstate-<account-id>` |
   | `TF_APPLY_ROLE` | `arn:aws:iam::<account-id>:role/meterbook-terraform-apply` (printed by step 1) |
   | `TF_PLAN_ROLE` | `arn:aws:iam::<account-id>:role/meterbook-terraform-plan` (after step 3) |
   | `TF_ADMIN_CIDRS` | JSON list of IPs allowed to reach the dev Kubernetes API, e.g. `["203.0.113.10/32"]` |

3. **Run the "Terraform bootstrap" workflow** (Actions → Terraform bootstrap → Run). It creates the state bucket and
   the plan role, adopts the seeded provider and apply role into Terraform, and moves its own state into the bucket.
4. **Run the "Terraform apply" workflow** with `environment: dev`. It applies dev, verifies it against the security
   baseline on real AWS (`tests/infra/verify-dev.sh`) and then destroys it, unless `destroy_after` is unchecked.
   Production applies use `environment: production` and are never destroyed by CI.

Pull requests that touch `infra/terraform/**` get a plan for dev and prod from the read-only plan role.

## Checks

```bash
make check   # fmt, no secret values, validate (all roots and modules), unit tests, tflint, trivy
```

Unit tests use `terraform test` with mocked providers, so they run offline with no AWS account. They cover every
security default above and every prod guard rail.
