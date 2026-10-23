# ADR-0007: Keycloak as authorization server and SSO broker, with tenants as Keycloak organizations and SCIM in the identity service

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T07
- **Deciders:** Mohit Raj

## Context

Meterbook's customers are companies. Their staff sign in to the portal and call the API; enterprise customers expect
to sign in with their own identity provider (Okta, Entra ID, Google Workspace, any SAML 2.0 or OIDC IdP) and to
provision and deprovision users automatically with SCIM 2.0. Every request must resolve to exactly one tenant, from
the credential and never from the request body (`api-standards.md`).

Constraints:

- Identity is the security boundary of the whole product; a bug here leaks every tenant's billing data.
- One engineer builds the platform; time spent on protocol plumbing is time not spent on billing.
- The product is standalone (ADR-0001) and runs on our own EKS clusters; no hosted identity vendor is assumed.
- Deprovisioning must take effect quickly: a user removed from the customer's IdP must lose access.

## Options considered

### Option A — Keycloak as authorization server and broker; tenants are Keycloak organizations; SCIM in our service
- Pros: OIDC, SAML 2.0 brokering, token signing and key rotation, sessions, brute-force protection and MFA come from
  a mature, CNCF-incubating, Apache-2.0 project. Keycloak *Organizations* (GA since 26.0) model exactly our tenant:
  members, email domains, and per-organization identity providers with domain-based routing at login.
- Cons: Keycloak has no SCIM server, so we build one. It is one more stateful component to run, upgrade and back up.

### Option B — Build the authorization server in the identity service (Spring Authorization Server + a SAML library)
- Pros: one component; full control of the data model.
- Cons: we would own token issuance, key rotation, session management and SAML signature validation — the code
  where identity products have their worst vulnerabilities. Weeks of work that customers never see.

### Option C — Zitadel
- Pros: built-in multi-tenancy (organizations), SCIM, and a modern API.
- Cons: smaller ecosystem and community than Keycloak; fewer engineers know it; less material for operating it at
  scale. Its SCIM is user-only for now, so group provisioning would still need work.

### Option D — Hosted vendor (Auth0, WorkOS, Cognito)
- Pros: nothing to operate.
- Cons: per-MAU or per-connection pricing grows with exactly the customers we want; enterprise SSO is a paid add-on;
  Cognito lacks SAML IdP-initiated flows and organizations. Ties a standalone product to a vendor.

## Decision

We will use **Option A**:

- **Keycloak 26** issues all tokens for the realm `meterbook`. Services validate JWTs (signature via JWKS, issuer,
  audience `meterbook-api`) and never see passwords or SAML assertions.
- **Tenant = Keycloak organization.** The tenant ID is the organization's ID. It reaches services in the
  `organization` claim; a token naming more or less than one organization is rejected.
- **Customer SSO** is a Keycloak identity provider (SAML 2.0 or OIDC) linked to the tenant's organization and routed
  by the user's email domain. A domain can belong to only one tenant. Tenant admins manage connections through our
  API (`/v1/sso-connections`); the identity service applies them through a small Keycloak admin adapter.
- **SCIM 2.0** (RFC 7643/7644) is served by the identity service under `/scim/v2`, authenticated by per-tenant
  bearer tokens stored only as SHA-256 hashes. SCIM is the source of truth for provisioned users and groups; each
  change is applied to Keycloak in the same request, so deactivation disables the Keycloak user and ends their
  sessions before the SCIM response returns.
- **Realm configuration is code** (`infra/keycloak/realm-meterbook.json`), imported at startup and used unchanged by
  the tests.

## Consequences

- **Positive:** the hard cryptographic and protocol work is done by a widely deployed project; the SSO end-to-end
  test exercises real SAML; adding OIDC customer IdPs later is a configuration change.
- **Negative / accepted trade-offs:** we operate Keycloak (database, upgrades, HA). Access tokens stay valid until
  they expire after deactivation, so their lifetime is kept short (5 minutes) and sessions are revoked on
  deactivation. A failed Keycloak call fails the SCIM request (503) rather than leaving the two stores apart.
- **Follow-up work:** projects and environments under a tenant, and Postgres row-level security (MB-T08); RBAC from
  SCIM groups (MB-T11); proof of domain ownership by DNS record before a domain is accepted (MB-T11); Keycloak HA
  and backups for production (MB-T16).

## How we will know it was wrong

- Keycloak upgrades or incidents cost more than two engineer-days per quarter, or
- a customer IdP we must support cannot be brokered by Keycloak, or
- login p99 (excluding the customer's IdP) exceeds 300 ms at 500 logins/s (LR-PLT-01) after tuning.
