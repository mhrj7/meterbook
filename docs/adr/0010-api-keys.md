# ADR-0010: Environment-scoped API keys, verified by the identity service

- **Status:** Accepted
- **Date:** 2026-10-24
- **Task:** MB-T09
- **Deciders:** Mohit Raj

## Context

Customers' servers call Meterbook without a person present: they send usage events, read invoices, sync customers.
They need long-lived credentials that a tenant admin issues, limits to what the integration needs, replaces without
downtime and can withdraw at once. A leaked key is the most likely way a tenant's billing data gets out, so a key
must reach as little as possible, must be useless if read from our database, and must stop working quickly when
revoked: within **60 seconds** everywhere (MB-T09, TC-PLT-003).

Keys belong to one environment (ADR-0009): a `test` key must never touch `live` data. Several services will accept
keys (ingest, invoicing and others, built later); only the identity service holds the key table.

## Options considered

### Option A — Signed self-contained tokens (JWTs) as API keys
- Pros: every service verifies locally; no call to identity.
- Cons: a signed token cannot be revoked before it expires without a deny-list every service must poll, which is
  the problem we started with; long-lived signed tokens also cannot be rotated without re-signing.

### Option B — Opaque random keys, hashed at rest, verified by each service against a replica of the key table
- Pros: no extra hop.
- Cons: every service needs read access to identity's data, breaking database-per-service; revocation depends on
  replication lag.

### Option C — Opaque random keys, hashed at rest, verified by the identity service, cached briefly by callers
- Pros: one owner of key data; revocation is a single row update; verification is one indexed hash lookup;
  services cache verdicts for a bounded time.
- Cons: one internal call per key per cache period; identity becomes a dependency of every machine request.

## Decision

We will use **Option C**.

- **Format:** `mb_live_` or `mb_test_` (the environment's kind, so a human or a secret scanner can tell them apart),
  then 40 random base62 characters (238 bits), then a 6-character base62 CRC-32 of everything before it. The checksum
  lets services and scanners reject typos and random strings without a lookup.
- **Storage:** only SHA-256 of the full key, plus the first 12 characters for display. A slow hash adds nothing for
  238-bit random secrets. The key is shown once, at creation; it is never stored, logged or kept in an idempotency
  record.
- **Scope:** a key belongs to exactly one tenant, project and environment, and carries a non-empty set of scopes from
  a fixed vocabulary (`usage:write`, `usage:read`, `customers:read`, `customers:write`, `invoices:read`,
  `invoices:write`, `catalog:read`, `catalog:write`). Services enforce the scopes their endpoints need. A key cannot
  manage keys, users or tenants: that stays with signed-in people.
- **Lifecycle:** tenant admins create, list (no secrets), rotate and revoke keys; members read. A key may have an
  expiry. **Rotation** issues a new key with the same name and scopes and sets the old key to expire after a grace
  period (default 24 h, at most 7 days, 0 for immediate) so integrations switch over without downtime. **Revocation**
  takes effect at once. At most 50 unrevoked keys per environment.
- **Verification:** `GET /v1/api-key` with the key as `Authorization: Bearer` returns the key's tenant, project,
  environment, kind and scopes, or 401. SDKs use it to check credentials; services use it to verify keys, and may
  cache a positive answer for at most **30 seconds** (`Cache-Control: private, max-age=30`) and must not cache a
  negative one. Identity itself checks the database on every request. Revocation therefore reaches every service
  within 30 s, inside the 60 s target.
- **Isolation:** the key table has forced row-level security. Looking up a presented key is a platform-scoped
  operation, as for SCIM tokens (ADR-0009), and returns only the one matching row.
- `last_used_at` is recorded at most once a minute per key, so admins can find unused keys without a write on every
  request.

## Consequences

- **Positive:** a database leak yields no working keys; a revoked key is dead everywhere within 30 s; a test key can
  never reach live data; rotation needs no downtime.
- **Negative / accepted trade-offs:** identity must be highly available for machine traffic (2+ replicas, PDB; its
  SLO covers `/v1/api-key`); services add a 30 s cache, so a revoked key may still be accepted for up to 30 s.
- **Follow-up work:** a shared Java client with the cache, written with the first service that accepts keys;
  secret-scanning partner registration for the `mb_live_` prefix once the product is public; per-key rate limits
  (MB-T12); audit events for key changes (MB-T11); mTLS for the internal call (MB-T10).
