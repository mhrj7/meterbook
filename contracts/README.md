# Contracts

Source of truth for every API (see `docs/standards/api-standards.md`).

- `openapi/` — public REST APIs (OpenAPI 3.1), one file per major version: `<service>-v<major>.yaml`
- `proto/` — internal gRPC APIs (protobuf, buf-managed)

| Contract | Service | Design |
|---|---|---|
| [`openapi/identity-v1.yaml`](openapi/identity-v1.yaml) | `services/identity` | [identity](../docs/design/identity.md) |

CI job **Contracts** lints every OpenAPI file with Spectral (`.spectral.yaml`: OpenAPI rules plus snake_case
fields, kebab-case paths, `Idempotency-Key` on every `POST`, `Request-Id` on every success) and fails on breaking
changes against the base branch (oasdiff). A breaking change needs a new major-version file.

```bash
docker run --rm -v "$PWD:/repo" -w /repo stoplight/spectral:6.15.0 lint 'contracts/openapi/*.yaml' --ruleset contracts/.spectral.yaml
```
