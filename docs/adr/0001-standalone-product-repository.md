# ADR-0001: Standalone product repository with per-service builds

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T01
- **Deciders:** Mohit Raj

## Context

Meterbook is sold as its own product. It must be buildable, deployable, demoable and sellable without any other product
of ours. It still contains several services, some in different languages (Java, TS / Python / Java, TypeScript), plus contracts,
infrastructure, docs and an evidence tracker that must change together.

## Options considered

### Option A — One repository for Meterbook, one directory per service, each with its native build tool
- Pros: a contract change and its consumers land in one PR; one CI config, one security baseline, one tracker;
  each language keeps idiomatic tooling.
- Cons: CI needs path filters so a change only builds affected services.

### Option B — One repository per service
- Pros: fully independent release cadence.
- Cons: contract changes span repos; duplicated CI and standards; harder to show the product as a whole.

### Option C — One monorepo shared with our other products
- Pros: shared platform code.
- Cons: couples products that must be sold separately; a buyer or reviewer would have to untangle them.

## Decision

We will use **Option A**. Meterbook lives in its own repository. Services live under `services/<name>/`, each with its
own build; shared internal code lives in `libs/`. The platform baseline (identity, tenancy, API keys, RBAC, audit,
quotas) is built inside this repo. Integrations with outside systems go through public APIs behind adapter
interfaces.

## Consequences

- **Positive:** the product can be cloned, run, and evaluated on its own; atomic contract changes; uniform CI.
- **Negative / accepted trade-offs:** platform patterns are reimplemented per product rather than shared; CI path
  filters need maintenance.
- **Follow-up work:** MB-T05 (CI with path filters).

## How we will know it was wrong

CI time for a typical PR exceeds 10 minutes despite path filters, or one service needs a release cadence the rest
of the repo cannot support.
