# API Standards

## Contracts

- REST APIs are specified in OpenAPI 3.1 under `contracts/openapi/`. Internal RPC uses protobuf under
  `contracts/proto/` (buf-managed).
- The contract is written and reviewed before implementation. CI lints contracts (Spectral, `buf lint`) and blocks
  breaking changes (`oasdiff`, `buf breaking`) unless the version is bumped.

## URLs and versioning

- `https://api.<domain>/v1/<resource>`; plural nouns; kebab-case paths; snake_case JSON fields.
- Major version in the path. Additive changes need no version bump; removals and semantic changes do.
- Deprecations announced with `Deprecation` and `Sunset` headers at least 90 days ahead.

## Authentication and tenancy

- `Authorization: Bearer <token>` (OIDC access token or API key). The tenant comes from the credential, never
  from a request body field.
- Every response for a resource belonging to another tenant is `404`, not `403`, to avoid leaking existence.

## Idempotency

- Every `POST` that creates or changes state accepts `Idempotency-Key` (client-generated, max 255 chars).
- Same key + same body within 24 h returns the stored response and status. Same key + different body returns
  `422 idempotency_key_reused`. Concurrent requests with one key: one executes, the others get `409` with
  `Retry-After`.

## Pagination, filtering, sorting

- Cursor pagination: `?limit=50&starting_after=<id>`; response contains `data`, `has_more`, `next_cursor`.
  Default limit 50, max 200.
- Filters as query params (`?status=active`); timestamps RFC 3339 UTC.

## Errors

One shape for every error (based on RFC 9457):

```json
{
  "error": {
    "type": "invalid_request",
    "code": "amount_must_be_positive",
    "message": "amount must be greater than 0",
    "param": "amount",
    "request_id": "req_01J..."
  }
}
```

| Status | Use |
|---|---|
| 400 | Malformed request |
| 401 | Missing or invalid credentials |
| 403 | Authenticated but not permitted (same tenant) |
| 404 | Not found or belongs to another tenant |
| 409 | Conflict (state or in-flight idempotency) |
| 422 | Valid syntax, failed validation |
| 429 | Rate limited; includes `Retry-After` |
| 5xx | Server fault; never leaks internals |

## Rate limits

Responses include `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` (IETF draft headers).

## Webhooks

- Signed with HMAC-SHA256 over `timestamp.payload`; header `Webhook-Signature: t=<ts>,v1=<sig>`; receivers reject
  timestamps older than 5 minutes.
- At-least-once delivery, exponential backoff for up to 3 days, then dead-letter; every event has a unique `id`
  for de-duplication and can be replayed via API.

## Observability

- Every request accepts and propagates W3C `traceparent`. Every response carries `Request-Id`.
