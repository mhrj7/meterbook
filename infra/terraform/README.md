# Meterbook infrastructure (Terraform)

AWS infrastructure for one Meterbook environment: network, Kubernetes, PostgreSQL, S3, and the IAM roles CI uses
to deploy. Decisions are recorded in [ADR-0002](../../docs/adr/0002-infrastructure-as-code.md).

## Layout

```
bootstrap/          one-time: Terraform state bucket (local state, run by an admin)
envs/dev/           dev root module   — disposable, cheap
envs/prod/          prod root module  — highly available, protected
modules/stack/      one complete environment; holds the dev-vs-prod guard rails
modules/network/    VPC: public / private / database / intra subnets, NAT, flow logs  (wraps terraform-aws-modules/vpc)
modules/cluster/    EKS, Graviton managed nodes, KMS-encrypted secrets, audit logs     (wraps terraform-aws-modules/eks)
modules/postgres/   RDS PostgreSQL: private, encrypted, TLS-only, RDS-managed password
modules/bucket/     S3: private, versioned, encrypted, TLS 1.2+ only
modules/github-oidc/ GitHub Actions -> AWS roles without static keys (plan: read-only, apply: protected envs)
```

## What each environment gets

| | dev | prod |
|---|---|---|
| Availability zones | 2 | 3 |
| NAT gateways | 1 shared | 1 per AZ |
| Kubernetes API | public, limited to `admin_cidrs` | private only |
| Nodes | 1–3 × t4g.medium/large, **spot** | 3–9 × m7g.large, on-demand |
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

## Cost estimate (us-east-1, on-demand list prices, before data transfer)

Rough figures for planning; confirm with the AWS Pricing Calculator before applying.

| Item | dev / month | prod / month |
|---|---|---|
| EKS control plane | ~$73 | ~$73 |
| Nodes | ~$15 (2 × t4g.medium spot) | ~$180 (3 × m7g.large) |
| NAT gateways | ~$33 | ~$99 |
| RDS (+ storage) | ~$15 | ~$250 (multi-AZ) |
| Logs, KMS, EBS, S3 | ~$10 | ~$40 |
| **Total** | **~$145** | **~$640** |

**dev is meant to be destroyed when idle** (`terraform destroy` in `envs/dev`); a weekday-only dev costs a few dollars a day.

## First-time setup

1. **Bootstrap the state bucket** (once per AWS account, with admin credentials):
   ```bash
   cd bootstrap
   terraform init
   terraform apply -var state_bucket_name=meterbook-tfstate-<account-id>
   ```
2. **Configure an environment:** in `envs/dev`, copy `backend.hcl.example` to `backend.hcl` and
   `terraform.tfvars.example` to `terraform.tfvars`, and fill in the values (both files are git-ignored).
3. **First apply** (creates the CI roles; later applies run from CI, MB-T05):
   ```bash
   cd envs/dev
   terraform init -backend-config=backend.hcl
   terraform plan -out=tfplan
   terraform apply tfplan
   ```
4. Set the `ci_roles` outputs as GitHub repository variables, and create the protected GitHub environments `dev` and
   `production` (required reviewers, `main` only).

## Checks

```bash
make check   # fmt, validate (all roots and modules), unit tests, tflint, trivy
```

Unit tests use `terraform test` with mocked providers, so they run offline with no AWS account. They cover every
security default above and every prod guard rail.
