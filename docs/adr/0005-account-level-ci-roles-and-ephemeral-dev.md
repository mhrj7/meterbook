# ADR-0005: Account-level CI roles owned by bootstrap, and an ephemeral dev environment

- **Status:** Accepted (amends ADR-0002)
- **Date:** 2026-10-23
- **Task:** MB-T03
- **Deciders:** Mohit Raj

## Context

ADR-0002 put the GitHub OIDC plan/apply roles inside each environment's stack. Two facts made that wrong:

1. The AWS account runs on a fixed credit budget, so dev must be destroyed after each working session. Destroying
   dev would also destroy the roles CI needs to create it again.
2. CI cannot create its own first role. Something has to exist before the first workflow runs.

## Options considered

### Option A — CI roles in `bootstrap/`, seeded once from CloudShell and adopted with `import` blocks
- Pros: roles outlive every environment; the hand-made seed (OIDC provider + apply role) becomes Terraform-managed
  on the first bootstrap run, so nothing stays click-ops; one plan role and one apply role for the account.
- Cons: one long-lived admin role; its scope is enforced by the trust policy (protected GitHub environments,
  `main` only) rather than by permissions.

### Option B — Keep roles per environment, never destroy dev
- Pros: matches ADR-0002.
- Cons: about USD 145/month for an idle dev; the credit budget would run out in weeks.

### Option C — Static access keys for the first run
- Pros: simplest.
- Cons: long-lived credentials to create, store and rotate; contradicts ADR-0002.

## Decision

We will use **Option A**:

- `scripts/cloudshell-bootstrap.sh` runs once per account (as an administrator, in AWS CloudShell) to create the
  GitHub OIDC provider and `meterbook-terraform-apply`, trusted only by this repository's `dev` and `production`
  GitHub environments. Both environments accept deployments from `main` only.
- The **Terraform bootstrap** workflow creates the state bucket and the read-only `meterbook-terraform-plan` role,
  adopts the seeded provider and apply role through `import` blocks, and migrates its own state into the bucket.
- The **Terraform apply** workflow, for dev, runs *apply → verify → destroy*. Verification checks the real resources
  against the security baseline through AWS APIs (`tests/infra/verify-dev.sh`). Destroy runs even if apply or
  verification fail, so a broken run cannot leave paid resources behind. prod is never destroyed by CI.
- The default region is **eu-north-1**.

## Consequences

- **Positive:** dev costs only while it is being used (roughly USD 1–3 per verification run); CI can always rebuild
  dev; no static keys anywhere.
- **Negative / accepted trade-offs:** the account has one admin CI role. Mitigations: OIDC trust limited to two
  protected environments on `main`, one-hour sessions, CloudTrail. A permissions boundary remains follow-up work.
- **Follow-up work:** a long-running dev for later tasks can be enabled per run (`destroy_after: false`).

## How we will know it was wrong

dev rebuilds become slow enough (EKS creation is about 15 minutes) to block daily work, or the team grows to need
per-environment CI identities. Then move to separate accounts per environment with their own bootstrap.
