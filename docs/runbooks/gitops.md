# Runbook: GitOps (ArgoCD)

- **Service:** platform
- **Design:** [ADR-0006](../adr/0006-gitops-with-argocd.md), layout in [`infra/argocd`](../../infra/argocd/README.md)
- **Owner:** Mohit Raj
- **Last tested:** 2026-10-23 (CI: GitOps end-to-end on kind; dev apply workflow on EKS)

## Open the ArgoCD UI

ArgoCD is not exposed publicly.

```bash
aws eks update-kubeconfig --name meterbook-dev --region eu-north-1
kubectl -n argocd port-forward svc/argocd-server 8080:80
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
# open http://localhost:8080, user admin
```

## Promote to production

```bash
scripts/promote-prod.sh            # promotes the current main
scripts/promote-prod.sh <commit>   # or a specific commit on main
```

The script refuses commits that are not on `main`, moves every pin in `infra/argocd/envs/prod`, runs the policy
check and opens a PR listing the commits that will reach prod. Review, wait for CI, merge. ArgoCD syncs prod within
one reconciliation interval (60 s).

## Roll back production

Promote the previous good commit: `scripts/promote-prod.sh <previous-sha>` (the PR title says what it is). Because
prod only ever runs a pinned commit, a rollback is the same reviewed path as a release.

## Diagnosis

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| Application `OutOfSync`, sync fails with "not permitted in project" | The chart rendered a kind the project does not allow | `kubectl -n argocd get application <app> -o yaml` → `status.conditions` | Add the kind to `projects/meterbook-apps.yaml` (reviewed change) or remove it from the chart |
| `platform-secrets-*` `Degraded` | A secret in AWS has no value yet, or the store is not Ready | `kubectl get clustersecretstore`, `kubectl -n meterbook-<env> describe externalsecret` | See [secrets runbook](secrets.md) |
| ApplicationSet generates no app for a new service | File name or path wrong | `kubectl -n argocd describe applicationset services-<env>` | Path must be `deploy/services/<name>/<env>.yaml` |
| Manual change in staging/prod disappears | Self-heal reverted it (by design) | Application history | Make the change in Git |
| GitOps policy check fails on a PR | Prod pin edited by hand, or dev/staging pinned | CI log lists each violation | Use `scripts/promote-prod.sh`; keep dev/staging on `main` |
