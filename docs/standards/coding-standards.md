# Coding Standards



## All languages

- **Readable over clever.** Names say what a thing is; functions do one thing.
- **Errors are values to handle, not to swallow.** Wrap with context, never log-and-continue silently. Return
  typed errors that map to API error codes (see `api-standards.md`).
- **Every network call has a timeout and a retry policy** (bounded retries, exponential backoff with jitter,
  retry only idempotent operations). Callers propagate deadlines.
- **Configuration from environment**, validated at startup; fail fast on missing or invalid config.
- **Structured JSON logs** with `trace_id`, `span_id`, `tenant_id`, `request_id`. Never log secrets, tokens, or
  PII. Log levels: `error` needs a human, `warn` is unexpected but handled, `info` is lifecycle, `debug` is off in
  production.
- **Comments explain why**, not what.
- **No dead code, no commented-out code, no TODO without a task ID** (`// TODO(MB-T12): ...`).
- **Dependencies:** pinned versions, a reason for every new one, and a licence compatible with commercial use.

## Java

Stack and versions: [ADR-0008](../adr/0008-java-service-stack.md).

- Java 25, Spring Boot 4.1, Gradle 9 Kotlin DSL (wrapper checked in, versions in `gradle/libs.versions.toml`).
  Virtual threads for blocking I/O.
- Immutability by default: `record` for DTOs and value objects.
- Money: `long` minor units plus `Currency`; never `double`; `BigDecimal` only for rate maths, with explicit
  rounding mode.
- SQL via jOOQ (generated from the Flyway migrations); migrations via Flyway, additive only. No JPA.
- Third-party clients (Keycloak, payment providers) sit behind an interface owned by the service.
- Lint: Error Prone. Format: Spotless (google-java-format). Coverage: JaCoCo gate.

## TypeScript

- Strict mode, ESM. Lint: `eslint`; format: `prettier`. Tests: `vitest`.

## Python

- Python 3.12+, `uv` for dependencies, FastAPI + Pydantic v2.
- Type hints everywhere; `mypy --strict` passes.
- Lint and format: `ruff`.
