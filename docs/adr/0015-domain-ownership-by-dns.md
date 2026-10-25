# ADR-0015: Tenants prove domain ownership with a DNS TXT record; only verified domains route SSO and SCIM

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** MB-T11
- **Deciders:** Mohit Raj

## Context

A tenant's email domains decide two things:
- which users Keycloak sends to the tenant's SSO connection (organization domains);
- which emails its SCIM client may provision (ADR-0007).

Claiming a domain therefore claims that company's users. Until now only a platform operator could add domains, at
tenant creation, and a domain could belong to one tenant only (docs/design/identity.md, NG4 and §9). Tenants cannot
add a domain themselves, for example after an acquisition, without a support ticket.

Constraints:
- Nobody may claim a domain they do not control.
- Two tenants must never both own a domain.
- A tenant waiting on its DNS change must not block, or be blocked by, another tenant's claim.
- Tests must not depend on real DNS.

## Options considered

### Option A: a DNS TXT record with a random token
- Pros: proves control of the zone; the standard (Google Workspace, Microsoft 365, Okta, Slack); works for domains
  that do not run a website.
- Cons: the customer must change DNS; propagation takes minutes to hours.

### Option B: an email to an address at the domain
- Pros: no DNS change.
- Cons: proves control of one mailbox, not the domain; an employee is not the company.

### Option C: a file served over HTTPS at the domain
- Pros: familiar from ACME.
- Cons: many corporate email domains serve no website, or one hosted by a third party.

## Decision

We will use **Option A**.

- **Claiming a domain:** `POST /v1/domains` (permission `domains:create`, ADR-0014) records the claim as `pending`
  with a 128-bit random token. The tenant publishes `meterbook-domain-verification=<token>` as a TXT record at
  `_meterbook-challenge.<domain>`.
- **Verifying:**
  - `POST /v1/domains/{id}/verify` looks the record up through a resolver interface. Production uses the JDK's DNS
    provider with a 2-second timeout; tests use a fake.
  - On a match the claim becomes `verified`, and the domain is added to the tenant's Keycloak organization in the
    same operation. If Keycloak fails, the claim stays pending.
- **First verified claim wins:**
  - Several tenants may hold pending claims for one domain.
  - A partial unique index on verified domains lets only one claim be verified. A later verification attempt is
    refused with `domain_already_verified`.
  - Removing a verified domain frees it for others.
- **Only verified domains count:**
  - Only verified domains reach the Keycloak organization, and so SSO routing.
  - SCIM accepts only emails in verified domains.
  - Pending claims have no effect.
- **Removing a domain:** `DELETE /v1/domains/{id}` removes it from the organization too. A tenant's last verified
  domain cannot be removed (`last_verified_domain`): its admins sign in through it.
- **Existing domains** are migrated as verified with method `operator`. They were asserted by a platform operator at
  tenant creation, which remains the way a tenant's first domains are set.
- **Limits:** at most 20 domains per tenant. Every claim, verification and removal is an audit event (ADR-0013).

## Consequences

- **Positive:**
  - Tenants add domains themselves.
  - Nobody can claim a domain without control of its DNS.
  - Competing claims cannot block each other.
- **Negative / accepted trade-offs:**
  - A domain is checked once, at verification. Losing the domain later is not noticed until re-verification exists.
  - DNS lookups depend on the cluster's resolvers.
- **Follow-up work:**
  - Periodic re-verification, which removes domains whose record is gone after a grace period.
  - Expiry of stale pending claims.

## How we will know it was wrong

- Customers regularly fail verification on correct records (resolver caching or split-horizon DNS), or
- a domain is claimed by the wrong tenant despite verification.
