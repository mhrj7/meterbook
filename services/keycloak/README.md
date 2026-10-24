# keycloak

Meterbook's authorization server and SSO broker ([ADR-0007](../../docs/adr/0007-identity-keycloak-broker-and-scim.md)):
the official Keycloak image, pre-built (`kc.sh build`) for PostgreSQL with health and metrics enabled. Starting with
`--optimized` means Keycloak never rewrites its install directory, so it runs on a read-only root filesystem with
only `/tmp` and `/opt/keycloak/data` writable.

The realm is configuration, not part of the image: `infra/keycloak/realm-meterbook.json`, mounted and imported at
start-up (`--import-realm`; existing realms are left alone).

| Port | Serves |
|---|---|
| 8080 | OIDC, SAML, admin API |
| 9000 | `/health/ready`, `/health/live`, `/metrics`; never exposed by the Service |
