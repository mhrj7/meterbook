# Runbook: Application secrets

- **Service:** platform (all services)
- **Design:** [ADR-0004](../adr/0004-secrets-management.md)
- **Owner:** Mohit Raj
- **Last tested:** 2026-10-23 (end-to-end test in CI: `tests/integration/secrets/e2e.sh`)

## How secrets flow

```
AWS Secrets Manager  meterbook-<env>/<name>   (KMS-encrypted, value set by an operator)
        │  read by External Secrets Operator (EKS Pod Identity role, exact ARNs only)
        ▼
Kubernetes Secret    <name> in namespace meterbook   (refreshed every hour)
        │  mounted as env vars / files
        ▼
Service pod
```

Terraform creates the empty secret shells; it never holds values.

## Set or change a secret value

Requires an AWS role allowed to call `secretsmanager:PutSecretValue` on `meterbook-<env>/*` (not the CI roles).

```bash
# Read the value from a file or prompt so it never lands in shell history.
read -rs VALUE
aws secretsmanager put-secret-value \
  --secret-id meterbook-dev/stripe-api-key \
  --secret-string "$VALUE"
unset VALUE
```

The new value reaches Kubernetes within the refresh interval (1 hour). To apply it immediately:

```bash
kubectl -n meterbook annotate externalsecret stripe force-sync="$(date +%s)" --overwrite
kubectl -n meterbook rollout restart deploy/<service>   # pods read env vars at start
```

## Rotate a secret

1. Create the new credential at the provider (e.g. roll the Stripe key), keeping the old one valid.
2. Set the new value (above) and force a sync.
3. Restart consumers and confirm healthy (dashboards, error rate).
4. Revoke the old credential at the provider.

The database password is rotated by RDS automatically; ESO picks up the new value on its next refresh.

## Suspected leak

1. Rotate the secret immediately (above), then revoke the old value at the provider.
2. Find who read it: CloudTrail `GetSecretValue` events for the secret ARN.
3. If it reached git: rotate first, then remove it from history; Gitleaks in CI should have blocked it — find
   out why it did not.
4. Open an incident and write a postmortem (`docs/slo/error-budget-policy.md`).

## Diagnosis

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| ExternalSecret `Ready=False`, reason `SecretSyncedError` | Secret has no value yet, or ESO role lacks access | `kubectl -n meterbook describe externalsecret <name>` | Set the value; add the secret to `app_secret_names` in Terraform |
| `AccessDeniedException` in ESO logs | Secret not in the ESO policy, or KMS denied | `kubectl -n external-secrets logs deploy/external-secrets` | Secrets must be created by the `app-secrets` module (exact ARNs) |
| ExternalSecret refused in a namespace | Namespace not in `store.allowedNamespaces` | ClusterSecretStore conditions | Add the namespace in the chart values (reviewed change) |
| Pod still has the old value | Env vars are read at start | Pod start time vs. secret update | Restart the deployment |
