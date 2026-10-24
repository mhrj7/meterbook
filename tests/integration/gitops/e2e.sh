#!/usr/bin/env bash
# End-to-end test for the GitOps layout (MB-T06, ADR-0006) on a disposable cluster (kind):
# installs ArgoCD, applies the projects and one cluster's Applications (dev and staging share a cluster; prod has its
# own) pinned to the commit under test, and checks
# that everything syncs, the smoke service runs hardened, its NetworkPolicy is enforced, and the ambient mesh rejects
# plaintext between pods (MB-T10, ADR-0011).
#   GITOPS_REVISION=<commit on GitHub> [E2E_ENVS="dev staging"|prod] tests/integration/gitops/e2e.sh
# Requires kubectl and helm, a throwaway kube context, and the commit to be pushed to the public repository.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REV="${GITOPS_REVISION:?set GITOPS_REVISION to a commit SHA that exists on GitHub}"
ARGOCD_CHART_VERSION="${ARGOCD_CHART_VERSION:-10.11.0}"
read -ra ENVS <<<"${E2E_ENVS:-dev staging}"
FIRST="${ENVS[0]}"                      # probed for NetworkPolicy and mesh
HEAL="${ENVS[${#ENVS[@]}-1]}"           # staging or prod: both self-heal
NAMESPACES=$(printf 'meterbook-%s ' "${ENVS[@]}")
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
    kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -20 || true
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

# The clusters' own ArgoCD values, with a shorter reconciliation interval so the test runs quickly.
ARGOCD_VALUES=(-f "$ROOT/infra/argocd/install/argocd-values.yaml" --set 'configs.cm.timeout\.reconciliation=20s')

step "Install ArgoCD $ARGOCD_CHART_VERSION"
helm repo add argo https://argoproj.github.io/argo-helm --force-update >/dev/null
helm upgrade --install argocd argo/argo-cd --version "$ARGOCD_CHART_VERSION" --namespace argocd --create-namespace \
  --wait --timeout 10m "${ARGOCD_VALUES[@]}"
ok "ArgoCD installed"

step "Projects and roots"
kubectl apply -f "$ROOT/infra/argocd/bootstrap/project.yaml" >/dev/null
kubectl apply -f "$ROOT/infra/argocd/projects/" >/dev/null
ok "bootstrap, platform and meterbook-apps projects created"
kubectl apply --dry-run=server -f "$ROOT/infra/argocd/roots/" >/dev/null
ok "root Applications valid against the ArgoCD CRDs (server-side dry run)"

step "Apply ${ENVS[*]} Applications at $REV"
# The roots track main and prod is pinned to a promoted commit; to test this commit, the child Applications are
# applied directly with main, or prod's pin, replaced by it.
for env in "${ENVS[@]}"; do
  for f in "$ROOT/infra/argocd/envs/$env"/*.yaml; do
    sed -E -e "s/(targetRevision|revision): (main|[0-9a-f]{40})$/\1: $REV/" "$f"
    echo "---"
  done
done | kubectl apply -f - >/dev/null
ok "${ENVS[*]} Applications and ApplicationSets created"

step "1. Everything syncs from Git"
MESH_APPS="platform-istio-base platform-istiod platform-istio-cni platform-ztunnel platform-mesh"
for app in $MESH_APPS platform-external-secrets $(printf 'platform-secrets-%s ' "${ENVS[@]}") $(printf 'smoke-%s ' "${ENVS[@]}"); do
  wait_until 600 "$app to be generated" kubectl -n argocd get application "$app"
  wait_until 600 "$app Synced" app_synced "$app"
  ok "$app Synced at $REV"
done
for app in $MESH_APPS platform-external-secrets $(printf 'smoke-%s ' "${ENVS[@]}"); do
  wait_until 600 "$app Healthy" app_healthy "$app"
  ok "$app Healthy"
done

# Every Application must at least render: a ComparisonError means ArgoCD could not build its manifests.
errors=$(kubectl -n argocd get applications -o json \
  | jq -r '.items[] | select(any(.status.conditions[]?; .type == "ComparisonError")) | .metadata.name')
if [ -n "$errors" ]; then die "ArgoCD cannot render: $errors"; fi
ok "every Application renders (no ComparisonError)"

step "2. The smoke service runs hardened in ${NAMESPACES% }"
for ns in $NAMESPACES; do
  wait_until 300 "smoke 2/2 in $ns" deploy_ready "$ns" smoke 2
  ok "smoke 2/2 available in $ns"
done
pods=$(kubectl -n "meterbook-$FIRST" get pods -l app.kubernetes.io/name=smoke \
  -o jsonpath='{range .items[*]}{.spec.securityContext.runAsNonRoot} {.spec.containers[0].securityContext.readOnlyRootFilesystem} {.spec.containers[0].securityContext.allowPrivilegeEscalation}{"\n"}{end}')
if grep -qv '^true true false$' <<<"$pods"; then die "smoke pods are not hardened: $pods"; fi
ok "smoke pods: non-root, read-only root filesystem, no privilege escalation"

step "3. NetworkPolicy is enforced"
probe() { # probe <namespace> <pod-name> [labels]
  kubectl -n "$1" run "$2" --image=busybox:1.37 --restart=Never --rm -i --quiet --labels="${3:-app=probe}" \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}}}}' \
    -- wget -q -T 5 -O- "http://smoke.meterbook-$FIRST.svc.cluster.local/healthz"
}
probe "meterbook-$FIRST" np-allowed >/dev/null || die "same-namespace request to smoke was blocked"
ok "same-namespace request to smoke allowed"
kubectl create namespace np-outsider --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if probe np-outsider np-denied >/dev/null 2>&1; then die "request from another namespace reached smoke"; fi
ok "request from another namespace blocked"

step "4. Plaintext between pods is rejected (ambient mesh, strict mTLS)"
for ns in $NAMESPACES; do
  [ "$(kubectl get namespace "$ns" -o 'jsonpath={.metadata.labels.istio\.io/dataplane-mode}')" = ambient ] \
    || die "$ns is not enrolled in the mesh"
done
ok "${NAMESPACES% } enrolled in the ambient mesh"
[ "$(kubectl -n istio-system get peerauthentication default -o 'jsonpath={.spec.mtls.mode}')" = STRICT ] \
  || die "mesh-wide PeerAuthentication is not STRICT"
ok "mesh-wide PeerAuthentication STRICT"
# Same namespace, so the NetworkPolicy admits it; outside the mesh, so it can only speak plaintext.
if probe "meterbook-$FIRST" plaintext-client istio.io/dataplane-mode=none >/dev/null 2>&1; then
  die "a plaintext client reached smoke despite strict mTLS"
fi
# It must be the mesh that refused it, not some other failure: ztunnel names the strict policy.
wait_until 60 "ztunnel to log the refused plaintext connection" bash -c \
  "kubectl -n istio-system logs -l app=ztunnel --since=5m --tail=-1 2>/dev/null | grep -F 'src.workload=\"plaintext-client\"' \
     | grep -qF 'policy rejection: explicitly denied by: istio-system/istio_converted_static_strict'"
ok "plaintext request from a pod outside the mesh rejected by strict mTLS, although the NetworkPolicy admits it"
probe "meterbook-$FIRST" mesh-client >/dev/null || die "an in-mesh client could not reach smoke"
ok "in-mesh request to smoke succeeds"
# ztunnel logs every connection it terminates with the peer's verified identity: proof the request went over mTLS.
wait_until 60 "ztunnel to log the mTLS connection" bash -c \
  "kubectl -n istio-system logs -l app=ztunnel --since=5m --tail=-1 2>/dev/null | grep -F 'dst.workload=\"smoke-' \
     | grep -qF 'src.identity=\"spiffe://cluster.local/ns/meterbook-$FIRST/sa/default\"'"
ok "ztunnel shows the request arriving over mTLS with the client's SPIFFE identity"

step "5. Drift is reverted in $HEAL (self-heal)"
kubectl -n "meterbook-$HEAL" scale deploy smoke --replicas=1 >/dev/null
wait_until 180 "$HEAL smoke restored to 2 replicas" deploy_ready "meterbook-$HEAL" smoke 2
ok "manual change in $HEAL reverted by ArgoCD"

echo
echo "---"
echo "$pass passed in $(( $(date +%s) - START ))s"
