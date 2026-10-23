# Meterbook

**Usage-based billing and double-entry ledger for SaaS companies.**

Meterbook lets SaaS companies charge for what their customers actually use: ingest usage events, price them with any plan model, generate invoices, collect payment, and keep an auditable double-entry ledger of every cent.

**Who it's for:** SaaS companies moving to usage-based or hybrid pricing (the market served by Orb, Metronome and Lago).

## Features (planned)

- Usage-event ingestion API with de-duplication (10k events/s target)
- Pricing engine: tiered, volume, per-seat, prepaid credits, commitments
- Invoices with proration, multi-currency and tax hooks
- Append-only double-entry ledger with continuous balance checks
- Dunning and payment retries as durable workflows
- Signed outbound webhooks with retries, dead-letter queue and replay
- Daily reconciliation against the payment provider
- Customer portal, admin console, SDKs (TypeScript, Python, Java)

Plus the platform every B2B SaaS needs: OIDC login, SAML SSO, SCIM provisioning, tenant isolation, scoped API
keys, RBAC, a customer-facing audit log, per-tenant rate limits and quotas, and self-serve plans.

## Tech stack

| Area | Choice |
|---|---|
| Services | Java 21, Spring Boot 3, jOOQ, Flyway |
| Data | PostgreSQL 16, Redis |
| Messaging | Kafka + Debezium (transactional outbox) |
| Workflows | Temporal |
| Payments | Stripe (behind an adapter interface) |
| APIs | REST (OpenAPI 3.1), gRPC internally |
| SDKs / portal | TypeScript, Python, Java |
| Platform | Kubernetes (EKS), Terraform, Helm, ArgoCD, GitHub Actions |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki, Tempo |

## Reliability, measured

Every task, test, SLO, load run and chaos experiment is tracked with evidence in
[`tracker/Meterbook_Tracker.xlsx`](tracker/) — open the **Dashboard** sheet first. A track is only marked *ready to ship* when every
test has run with no failures, every SLO is measured and met, every load and chaos run passes, and no critical
defects are open.

## Status

| Track | Status |
|---|---|
| Platform baseline | In progress |
| Product | Planned |

## Docs

- Engineering rules (start here): [`docs/standards/engineering-rules.md`](docs/standards/engineering-rules.md)
- Architecture decisions: [`docs/adr/`](docs/adr/)
- Design docs: [`docs/design/`](docs/design/)
- SLOs and error-budget policy: [`docs/slo/`](docs/slo/)
- Engineering standards: [`docs/standards/`](docs/standards/)
- API contracts: [`contracts/`](contracts/)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
