# GitOps (ArgoCD)

Everything that runs in Meterbook's clusters is declared here and synced by ArgoCD. Decisions:
[ADR-0006](../../docs/adr/0006-gitops-with-argocd.md). Operations: [runbook](../../docs/runbooks/gitops.md).

## Layout

```
install/argocd-values.yaml  ArgoCD chart values — used by Terraform and by the end-to-end test
bootstrap/project.yaml   project for the roots only (ArgoCD objects in the argocd namespace) — created by Terraform
roots/<env>.yaml         one root Application per environment, tracking main — created by Terraform
projects/                platform (cluster components) and meterbook-apps (services, least privilege)
envs/dev/                ESO, Istio ambient mesh, platform-secrets-dev, identity,  → follows main
                         services ApplicationSet
envs/staging/            platform-secrets-staging, identity, services ApplicationSet → follows main, self-heal
envs/prod/               ESO, Istio ambient mesh, platform-secrets-prod, identity, → pinned to one commit SHA
                         services ApplicationSet
```

## How a change reaches each environment

| Environment | Cluster / namespace | Change arrives | Drift |
|---|---|---|---|
| dev | dev / `meterbook-dev` | on merge to `main` | left alone (experiment freely) |
| staging | dev / `meterbook-staging` | on merge to `main` | reverted automatically |
| prod | prod / `meterbook-prod` | when a promotion PR moves the pin (`scripts/promote-prod.sh`) | reverted automatically |

## Adding a service

Create `deploy/services/<name>/<env>.yaml` with the service's values for the
[`meterbook-service`](../helm/meterbook-service/values.yaml) chart. The environment's ApplicationSet picks it up
as `<name>-<env>`; nothing else changes.

## Checks

- `scripts/gitops_policy.py` (CI job **GitOps policy**): dev/staging track `main`, prod pins one commit that is on
  `main`, charts use exact versions, staging/prod self-heal, services stay in `meterbook-<env>`.
- `tests/integration/gitops/e2e.sh` (CI job **GitOps end-to-end**): ArgoCD on kind syncs dev and staging at the
  commit under test; checks health, hardening, NetworkPolicy enforcement and self-heal.
- `tests/infra/verify-dev.sh` (dev apply workflow): the same on real EKS, plus secrets delivered from AWS Secrets
  Manager into the cluster.
