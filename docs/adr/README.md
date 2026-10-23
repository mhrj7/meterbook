# Architecture Decision Records

New ADR: copy [`template.md`](template.md) to `NNNN-short-title.md` with the next number, open a PR, and add it
to this index.

| ADR | Title | Status | Task |
|---|---|---|---|
| [0000](0000-record-architecture-decisions.md) | Record architecture decisions | Accepted | MB-T01 |
| [0001](0001-standalone-product-repository.md) | Standalone product repository with per-service builds | Accepted | MB-T01 |
| [0002](0002-infrastructure-as-code.md) | Infrastructure as code with Terraform, composed environments and S3-native state locking | Accepted | MB-T03 |
| [0003](0003-ci-and-supply-chain.md) | CI on GitHub Actions with a single required check and a signed, scanned image supply chain | Accepted | MB-T05 |
| [0004](0004-secrets-management.md) | Secrets in AWS Secrets Manager, delivered to pods by External Secrets Operator with EKS Pod Identity | Accepted | MB-T04 |
