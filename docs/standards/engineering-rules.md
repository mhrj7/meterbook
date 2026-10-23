# Engineering Rules

Rules for everyone working in this repo. Read this first; it overrides habits.

## What this repo is

**Meterbook** — Usage-based billing and double-entry ledger for SaaS companies. A standalone, sellable SaaS product with its own platform baseline (identity, tenancy,
API keys, RBAC, audit, quotas, billing) and its own plan: `tracker/Meterbook_Tracker.xlsx` (sheet `Plan`). Every change maps to a task
ID such as `MB-T07`.

| Component | Language | Purpose |
|---|---|---|
| `services/identity` | Java | Platform: OIDC, SSO, SCIM, API keys, RBAC, audit |
| `services/ingest` | Java | Usage-event ingestion and de-duplication |
| `services/ledger` | Java | Double-entry ledger |
| `services/rating` | Java | Pricing engine |
| `services/invoicing` | Java | Invoices, dunning workflows, payment adapter |
| `services/webhooks` | Java | Outbound webhook delivery |
| `web/portal` | TypeScript | Customer portal and admin console |
| `sdks/` | TS / Python / Java | Generated client SDKs |

Components are added when their task starts; the table is the target shape.

## Non-negotiables

1. **Contracts first.** Public and internal APIs are defined in `contracts/` (OpenAPI for REST, protobuf for gRPC) before implementation. Code conforms to the contract; never edit a contract silently to match code.
2. **Every behaviour change ships with tests** in the same PR. See `docs/standards/testing-standards.md` for which test types each change needs.
3. **No secrets in git.** Use `.env.example` with placeholder values; real values come from the secret store.
4. **Significant decisions get an ADR** in `docs/adr/` (template: `docs/adr/template.md`). "Significant" = hard to reverse, affects more than one service, or picks a technology.
5. **Tenant isolation is a security boundary.** Every query that touches tenant data is scoped by `tenant_id` and covered by an isolation test.
6. **Idempotent writes.** Every mutating public endpoint accepts an `Idempotency-Key` header.
7. **Observable by default.** Every service exports OpenTelemetry traces, RED metrics and structured JSON logs with `trace_id` and `tenant_id`.
8. **Standalone product.** Meterbook depends on no other product of ours. Integrations with outside systems go through their public APIs behind an adapter interface.
9. **Do not claim done without evidence.** "Done" means the task's Definition of Done in the tracker is met and the CI run is green. Paste the evidence link into the tracker.
10. **Money is integers.** Amounts are integer minor units plus an ISO-4217 currency code. No floating point for money anywhere; `BigDecimal` only for rate maths with an explicit rounding mode.
11. **The ledger is append-only.** Corrections are new reversing entries; nothing is updated or deleted, and `sum(debits) = sum(credits)` is checked continuously.

## Layout

```
contracts/      OpenAPI + protobuf: the source of truth for every API
services/       deployable services, one directory each, each with its own build
libs/           shared internal libraries
web/            customer-facing UI
sdks/           client SDKs (generated from contracts where possible)
infra/          terraform/ (AWS), helm/ (charts), argocd/ (GitOps apps)
deploy/local/   docker compose stack for local development
tests/          cross-service load/ and chaos/ suites
docs/           adr/, design/, slo/, runbooks/, postmortems/, standards/
tracker/        the plan and evidence workbook
```

## Standards (read the one you need)

- Coding: `docs/standards/coding-standards.md`
- Testing: `docs/standards/testing-standards.md`
- APIs: `docs/standards/api-standards.md`
- Git and PRs: `docs/standards/git-workflow.md`
- SLOs: `docs/slo/template.md` and `docs/slo/error-budget-policy.md`

## Before opening a PR

- [ ] Task ID in branch name and PR title (`MB-T07: ...`)
- [ ] Lint, unit and integration tests pass locally
- [ ] New or changed behaviour has tests at the levels the testing standard requires
- [ ] Contract, ADR and docs updated if affected
- [ ] Tracker rows updated (Plan status, Test_Cases results, Metrics measured values)
