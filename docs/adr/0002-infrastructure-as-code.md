# ADR-0002: Infrastructure as code with Terraform, composed environments and S3-native state locking

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T03
- **Deciders:** Mohit Raj

## Context

Meterbook runs on AWS: a Kubernetes cluster for its services, PostgreSQL for the ledger, and S3 for invoice
documents and exports. Infrastructure must be reproducible per environment (dev, prod), reviewable in PRs,
applied only from CI, and cheap enough to tear down when idle. Billing data makes encryption, private networking
and backups non-optional.

## Options considered

### Option A — Terraform, community modules for VPC/EKS, own modules for data stores and IAM
- Pros: industry standard; the `terraform-aws-modules` VPC and EKS modules encode years of edge cases; owning the
  RDS, S3 and IAM modules keeps security-critical settings explicit and unit-testable with `terraform test`.
- Cons: two upstream modules to keep upgraded.

### Option B — Terraform with every module written from scratch
- Pros: full control.
- Cons: re-implements well-tested VPC/EKS plumbing; more surface for subtle mistakes.

### Option C — AWS CDK or Pulumi
- Pros: general-purpose languages.
- Cons: less common in platform teams' hiring bar; harder to review plans as diffs; ties infra to one language.

## Decision

We will use **Option A**:

- `infra/terraform/modules/stack` composes the whole environment from `network` (wraps
  `terraform-aws-modules/vpc`), `cluster` (wraps `terraform-aws-modules/eks`), `postgres`, `bucket` and
  `github-oidc`. Environments (`envs/dev`, `envs/prod`) are thin root modules that differ only in inputs.
- **State** lives in a versioned, encrypted S3 bucket created by `bootstrap/`, with **S3-native locking**
  (`use_lockfile = true`, Terraform >= 1.10). This replaces the DynamoDB lock table named in the original task
  definition: HashiCorp has deprecated DynamoDB locking, and one fewer resource means one fewer thing to secure.
- **CI applies, humans don't.** GitHub Actions assumes AWS roles through OIDC — no long-lived keys. Pull requests
  get a read-only *plan* role; only `main` in the protected `production`/`dev` environments gets the *apply* role.
- **Secure defaults are enforced in our modules, not left to callers:** RDS is private, encrypted, TLS-only, with
  the master password generated and stored by RDS in Secrets Manager (never in Terraform state); buckets block all
  public access, require TLS and are versioned; the EKS API is private by default and secrets are KMS-encrypted.
- Provider and module versions are pinned with `~>` constraints and a committed lock file.

## Consequences

- **Positive:** reproducible environments; every security-critical setting has a unit test; no static cloud
  credentials anywhere.
- **Negative / accepted trade-offs:** the apply role is broad (AdministratorAccess) because it creates IAM and
  networking; it is constrained by branch and GitHub environment protection rather than by policy scope. A
  permissions boundary is follow-up work once the resource set stabilises.
- **Cost:** dev runs at roughly USD 145/month if left on (EKS control plane, two small spot nodes, one NAT gateway,
  a micro RDS instance) and prod at roughly USD 640/month; dev is designed to be destroyed when idle. Estimates in
  `infra/terraform/README.md`.
- **Follow-up work:** MB-T04 (secrets operator), MB-T05 (CI plan/apply), MB-T06 (Helm + ArgoCD on the cluster).

## How we will know it was wrong

Plans regularly take more than 5 minutes, upstream module upgrades repeatedly force resource replacement, or
reviewers cannot read plans as diffs. Then split state per component or revisit Option B for the cluster.
