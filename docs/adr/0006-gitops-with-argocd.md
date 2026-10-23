# ADR-0006: GitOps with ArgoCD, a shared Helm library chart, and SHA-pinned production

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T06
- **Deciders:** Mohit Raj

## Context

Meterbook will run several services on EKS (identity, ingest, ledger, rating, invoicing, webhooks) plus platform
components (External Secrets Operator, the secrets chart). Every service needs the same production defaults: non-root
containers, read-only filesystems, resource limits, probes, disruption budgets, network policies, autoscaling.
Deployments must be reviewable, reproducible and reversible, and production must never change because something
merged to `main`.

There are two clusters: a disposable **dev** cluster (created and destroyed per verification session, ADR-0005) and
**prod**. The plan calls for dev, staging and prod environments.

## Options considered

### Option A — ArgoCD app-of-apps per environment, one library chart, prod pinned by commit SHA
- Pros: the cluster converges on what Git says (drift is corrected); every change is a commit; rollback is a revert;
  ArgoCD's UI and health model show exactly what runs where.
- Cons: ArgoCD itself has to be installed and kept up to date.

### Option B — Flux
- Pros: lighter, CRD-only.
- Cons: no built-in UI for reviewers; ArgoCD is more common in the target roles.

### Option C — `helm upgrade` from CI (push-based)
- Pros: simplest.
- Cons: CI needs cluster-admin credentials and network access to every cluster; no drift correction; the cluster
  state is whatever the last pipeline did.

## Decision

We will use **Option A**:

- **Charts.** `infra/helm/meterbook-lib` is a Helm *library* chart holding the hardened templates (Deployment,
  Service, ServiceAccount, HPA, PodDisruptionBudget, NetworkPolicy). `infra/helm/meterbook-service` is the one
  application chart every service deploys with; a service is just a values file. Unsafe settings (root user,
  writable root filesystem, privilege escalation, missing limits) cannot be expressed through values.
- **Environments.**

  | Environment | Cluster | Namespace | Follows | Sync |
  |---|---|---|---|---|
  | dev | dev | `meterbook-dev` | `main` | automated, no self-heal (engineers may experiment) |
  | staging | dev | `meterbook-staging` | `main` | automated with prune and self-heal |
  | prod | prod | `meterbook-prod` | a pinned commit SHA | automated with prune and self-heal, to the pinned SHA |

  Staging is a namespace on the dev cluster rather than a third cluster, to keep verification sessions affordable.
  It uses the dev AWS resources.
- **App-of-apps.** Terraform installs ArgoCD and one root Application per environment on the cluster; each root
  tracks `main` and reads `infra/argocd/envs/<env>/`, which holds that environment's Applications and
  ApplicationSets. Services are discovered by an ApplicationSet from `deploy/services/<name>/<env>.yaml`.
- **Promotion.** Every Application under `infra/argocd/envs/prod/` pins `targetRevision` to the same full commit SHA.
  Production changes only through a pull request that moves that pin (`scripts/promote-prod.sh <sha>`). CI's
  `gitops-policy` check fails if prod tracks a branch, if prod pins disagree, if the SHA is not on `main`, or if
  dev/staging stop tracking `main`.
- **Access.** The repository is public, so ArgoCD reads it over HTTPS without credentials. ArgoCD is not exposed
  publicly; operators use `kubectl port-forward`.
- **Bootstrap reach.** CI can reach the dev cluster's API (its runner IP is added to the dev allow-list for the
  length of a run). The prod API is private, so the prod GitOps bootstrap is applied from inside the VPC; it is off
  by default (`bootstrap_gitops = false`) until that path exists.

## Consequences

- **Positive:** one hardened chart for every service; prod changes are explicit, reviewed and auditable; drift in
  staging and prod is reverted automatically; rollback is a Git revert.
- **Negative / accepted trade-offs:** staging shares the dev cluster and AWS resources, so it cannot catch
  cluster-level differences; prod GitOps bootstrap needs an in-VPC runner (follow-up); ArgoCD adds a component to
  upgrade.
- **Follow-up work:** MB-T07+ add services as `deploy/services/<name>/` values; signature verification on admission
  (ADR-0003); in-VPC bootstrap path for prod.

## How we will know it was wrong

Promotions routinely bypass the pin (manual `kubectl` in prod), or staging regularly passes changes that then fail in
prod because of cluster differences. Then add a real staging cluster or move to per-environment accounts.
