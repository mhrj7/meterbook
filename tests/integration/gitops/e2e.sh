#!/usr/bin/env bash
# End-to-end test for the GitOps layout (MB-T06, ADR-0006) on a disposable cluster (kind):
# installs ArgoCD, applies the projects and the dev/staging Applications pinned to the commit under test, and checks
# that everything syncs, the smoke service runs hardened, and its NetworkPolicy is enforced.
#   GITOPS_REVISION=<commit on GitHub> tests/integration/gitops/e2e.sh
# Requires kubectl and helm, a throwaway kube context, and the commit to be pushed to the public repository.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REV="${GITOPS_REVISION:?set GITOPS_REVISION to a commit SHA that exists on GitHub}"
ARGOCD_CHART_VERSION="${ARGOCD_CHART_VERSION:-10.11.0}"
START=$(date +%s)
pass=0

step() { echo; echo "=== $*"; }
ok()   { echo "PASS  $*"; pass=$((pass + 1)); }
die()  { echo "FAIL  $*"; exit 1; }

on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    echo; echo "=== Diagnostics"
    kubectl -n argocd get applications -o wide 2>/dev/null || true
    for a in $(kubectl -n argocd get applications -o name 2>/dev/null); do
      kubectl -n argocd get "$a" -o jsonpath='{.metadata.name}{": "}{.status.conditions}{" "}{.status.operationState.message}{"\n"}' 2>/dev/null || true
    done
    kubectl get pods -A 2>/dev/null | grep -v Running || true
  fi
  exit "$rc"
}
trap on_exit EXIT

wait_until() { # wait_until <seconds> <description> <command...>
  local timeout=$1 what=$2; shift 2
  local deadline=$((SECONDS + timeout))
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -ge "$deadline" ] && die "timed out after ${timeout}s waiting for: $what"
    sleep 5
  done
}
app_field() { kubectl -n argocd get application "$1" -o "jsonpath={$2}" 2>/dev/null; }
app_synced() { [ "$(app_field "$1" .status.sync.status)" = Synced ]; }
app_healthy() { [ "$(app_field "$1" .status.health.status)" = Healthy ]; }
deploy_ready() { [ "$(kubectl -n "$1" get deploy "$2" -o 'jsonpath={.status.availableReplicas}')" = "$3" ]; }

step "Install ArgoCD $ARGOCD_CHART_VERSION"
helm repo add argo https://argoproj.github.io/argo-helm --force-update >/dev/null
helm upgrade --install argocd argo/argo-cd --version "$ARGOCD_CHART_VERSION" --namespace argocd --create-namespace \
  --wait --timeout 10m \
  --set dex.enabled=false --set notifications.enabled=false \
  --set 'configs.params.server\.insecure=true' --set 'configs.cm.timeout\.reconciliation=20s'
ok "ArgoCD installed"

step "Projects and roots"
kubectl apply -f "$ROOT/infra/argocd/bootstrap/project.yaml" >/dev/null
kubectl apply -f "$ROOT/infra/argocd/projects/" >/dev/null
ok "bootstrap, platform and meterbook-apps projects created"
kubectl apply --dry-run=server -f "$ROOT/infra/argocd/roots/" >/dev/null
ok "root Applications valid against the ArgoCD CRDs (server-side dry run)"

step "Apply dev and staging Applications at $REV"
# The roots track main; to test this commit, the child Applications are applied directly with main replaced by it.
for env in dev staging; do
  for f in "$ROOT/infra/argocd/envs/$env"/*.yaml; do
    sed -e "s/targetRevision: main$/targetRevision: $REV/" -e "s/revision: main$/revision: $REV/" "$f"
    echo "---"
  done
done | kubectl apply -f - >/dev/null
ok "dev and staging Applications and ApplicationSets created"

step "1. Everything syncs from Git"
for app in platform-external-secrets platform-secrets-dev platform-secrets-staging smoke-dev smoke-staging; do
  wait_until 600 "$app to be generated" kubectl -n argocd get application "$app"
  wait_until 600 "$app Synced" app_synced "$app"
  ok "$app Synced at $REV"
done
for app in platform-external-secrets smoke-dev smoke-staging; do
  wait_until 600 "$app Healthy" app_healthy "$app"
  ok "$app Healthy"
done

step "2. The smoke service runs hardened in both namespaces"
for ns in meterbook-dev meterbook-staging; do
  wait_until 300 "smoke 2/2 in $ns" deploy_ready "$ns" smoke 2
  ok "smoke 2/2 available in $ns"
done
pods=$(kubectl -n meterbook-dev get pods -l app.kubernetes.io/name=smoke \
  -o jsonpath='{range .items[*]}{.spec.securityContext.runAsNonRoot} {.spec.containers[0].securityContext.readOnlyRootFilesystem} {.spec.containers[0].securityContext.allowPrivilegeEscalation}{"\n"}{end}')
if grep -qv '^true true false$' <<<"$pods"; then die "smoke pods are not hardened: $pods"; fi
ok "smoke pods: non-root, read-only root filesystem, no privilege escalation"

step "3. NetworkPolicy is enforced"
probe() {
  kubectl -n "$1" run "$2" --image=busybox:1.37 --restart=Never --rm -i --quiet \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}}}}' \
    -- wget -q -T 5 -O- http://smoke.meterbook-dev.svc.cluster.local/healthz
}
probe meterbook-dev np-allowed >/dev/null || die "same-namespace request to smoke was blocked"
ok "same-namespace request to smoke allowed"
kubectl create namespace np-outsider --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if probe np-outsider np-denied >/dev/null 2>&1; then die "request from another namespace reached smoke"; fi
ok "request from another namespace blocked"

step "4. Drift is reverted in staging (self-heal)"
kubectl -n meterbook-staging scale deploy smoke --replicas=1 >/dev/null
wait_until 180 "staging smoke restored to 2 replicas" deploy_ready meterbook-staging smoke 2
ok "manual change in staging reverted by ArgoCD"

echo
echo "---"
echo "$pass passed in $(( $(date +%s) - START ))s"
