# ADR-0003: CI on GitHub Actions with a single required check and a signed, scanned image supply chain

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T05
- **Deciders:** Mohit Raj

## Context

Meterbook handles customers' money. Every change must be linted, tested and scanned before merge, and every
container image that reaches the cluster must be traceable to a reviewed commit and free of known critical
vulnerabilities. The repository will hold Terraform, several Java services, a TypeScript portal and SDKs, so CI
must only run what a change touches and still stay under 10 minutes.

## Options considered

### Option A — GitHub Actions, path-filtered jobs, one aggregating `ci` check
- Pros: native to the repository; OIDC to AWS and Sigstore without stored secrets; one stable required check no
  matter which jobs were skipped.
- Cons: third-party actions are a supply-chain risk of their own.

### Option B — External CI (CircleCI, Buildkite)
- Pros: more control over runners.
- Cons: another vendor and another set of credentials; no advantage at this scale.

## Decision

We will use **Option A** with these rules:

- **Path filters** decide which jobs run; a final `ci` job fails if any job failed or was cancelled and is the only
  required status check.
- **Every third-party action is pinned to a full commit SHA** (tag in a comment). Dependabot proposes updates
  weekly, so pins do not rot.
- **No long-lived cloud credentials.** AWS access uses OIDC roles (ADR-0002); image signing uses keyless cosign
  with the workflow's OIDC identity.
- **Images:** built once, scanned with Trivy (fixable CRITICAL fails the build), described by an SPDX SBOM
  (Syft), and on `main` pushed to GHCR, signed, and given an SBOM attestation. Cluster admission can then require
  a valid signature from this repository's workflow (follow-up with MB-T06).
- **Secrets:** Gitleaks scans the full history on every run.
- **Infrastructure:** fmt, validate, `terraform test`, TFLint and Trivy config scan on every change; plans on PRs
  with the read-only role; applies only from protected GitHub environments.

## Consequences

- **Positive:** reviewable, reproducible pipeline; provenance for every image; a leaked workflow cannot reach
  AWS outside the protected environments.
- **Negative / accepted trade-offs:** SHA pins make action upgrades a Dependabot PR rather than automatic;
  `ignore-unfixed` means a CRITICAL with no available fix is reported but does not block (otherwise an upstream
  issue could freeze all releases).
- **Follow-up work:** MB-T06 (admission policy verifying signatures; done in ADR-0012), branch protection on `main`
  requiring `ci`.

## How we will know it was wrong

Median PR pipeline time exceeds 10 minutes, or engineers routinely bypass the `ci` check.
