# ADR-0008: Java service stack — Java 25, Spring Boot 4.1, Gradle 9, jOOQ and Flyway

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T07
- **Deciders:** Mohit Raj

## Context

The identity service is the first Java service; every later Meterbook service (ingest, ledger, rating, invoicing,
webhooks) copies its build. The coding standard said Java 21 and Spring Boot 3, written before any service existed.
Since then Spring Boot 3.5's open-source support ended (2026-06-30) and Java 25 became the current LTS. Starting the
first service on an unsupported framework line would mean a forced migration across six services within months.

## Options considered

### Option A — Java 25 LTS, Spring Boot 4.1 (OSS support to 2027-07-31), Gradle 9 Kotlin DSL
- Pros: supported lines for the next year at least; Boot 4 brings first-class OpenTelemetry, structured JSON logging
  and HTTP service clients; Java 25 virtual threads without pinning on `synchronized`.
- Cons: some libraries lag a major Boot release; fewer examples online than for Boot 3.

### Option B — Java 21, Spring Boot 3.5
- Pros: more examples.
- Cons: already out of open-source support; a migration is owed before production.

### Option C — Quarkus or Micronaut
- Pros: faster start-up, smaller memory.
- Cons: smaller hiring pool and ecosystem; Spring Security's OAuth2 resource server and testing support are what
  identity needs most.

## Decision

We will use **Option A** for every Java service:

| Concern | Choice |
|---|---|
| Runtime | Java 25 (Temurin), virtual threads for blocking I/O |
| Framework | Spring Boot 4.1, Spring Security OAuth2 resource server |
| Build | Gradle 9 Kotlin DSL with the wrapper checked in; dependency versions in `gradle/libs.versions.toml`; dependency locking |
| SQL | jOOQ (code generated from the Flyway migrations, no database needed at build time); Flyway migrations |
| Tests | JUnit 5, AssertJ, Testcontainers 2 (real PostgreSQL and Keycloak), Playwright for browser end-to-end |
| Quality gates | Spotless (google-java-format), Error Prone, JaCoCo (90% line / 85% branch), dependency and image scans with Trivy |
| Image | distroless Java 25 base, non-root, built and signed by the shared container pipeline (ADR-0003) |
| Observability | Spring Boot OpenTelemetry starter (OTLP traces), Micrometer Prometheus metrics, structured JSON logs |

`docs/standards/coding-standards.md` is updated to match.

## Consequences

- **Positive:** no framework migration owed; one build template for six services.
- **Negative / accepted trade-offs:** we track Spring Boot minor releases (about two per year) to stay supported.
- **Follow-up work:** a shared Gradle convention plugin once a second Java service exists (MB-T16+).

## How we will know it was wrong

A required library has no release compatible with Spring Boot 4 within three months of needing it, or start-up time
above 10 s blocks autoscaling.
