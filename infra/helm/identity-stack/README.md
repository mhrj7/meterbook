# identity-stack

Keycloak and the identity service for one environment (MB-T07, [ADR-0007](../../../docs/adr/0007-identity-keycloak-broker-and-scim.md)),
deployed by ArgoCD into `meterbook-<env>`.

| Sync wave | What | Why first |
|---|---|---|
| -3 | `Password` generator | the ExternalSecrets below use it |
| -2 | ExternalSecrets: `database` (host, port, master secret ARN from `meterbook-<env>/database`), and generated `keycloak-db`, `identity-db`, `keycloak-clients`, `keycloak-admin`; realm ConfigMap; `db-bootstrap` service account | credentials and config must exist before anything uses them |
| -1 | `db-bootstrap` Job (Sync hook) | creates `keycloak_<env>` and `identity_<env>` (role + database) with the generated passwords |
| 0 | Keycloak, identity (two `meterbook-service` releases) | |

- **No credentials in Git or Terraform state.** Service passwords and Keycloak client secrets are generated in the
  cluster (ESO `Password` generator, `refreshPolicy: CreatedOnce`). The RDS master credentials are read only by the
  `db-bootstrap` Job, whose IAM role can read that one secret.
- **One database per service per environment**; each role can connect only to its own (`REVOKE ALL … FROM PUBLIC`).
- **The realm** is `files/realm-meterbook.json`, a link to `infra/keycloak/realm-meterbook.json`.
- staging shares the dev cluster and database instance (`databaseSecretEnvironment: dev`) with its own databases.

```bash
infra/helm/identity-stack/tests/render_test.sh
```
