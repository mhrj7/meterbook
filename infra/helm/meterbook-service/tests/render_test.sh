#!/usr/bin/env bash
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else in this file.
# shellcheck disable=SC2015
# Render tests for meterbook-service (and the meterbook-lib templates it uses).
# Requires helm, kubeconform and kube-linter on PATH.
#   infra/helm/meterbook-service/tests/render_test.sh
set -euo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$CHART/../../.." && pwd)"
SMOKE="$ROOT/deploy/services/smoke/dev.yaml"
pass=0
fail=0

ok()  { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }
render() { helm template smoke "$CHART" "$@"; }
has() { grep -qE -- "$1" <<<"$2"; }
count() { grep -cE -- "$1" <<<"$2" || true; }

expect_render_fails() {
  local name=$1 message=$2; shift 2
  local out
  if out=$(render "$@" 2>&1); then
    bad "$name (rendered but should have failed)"
  elif grep -q -- "$message" <<<"$out"; then
    ok "$name"
  else
    bad "$name (failed for the wrong reason: $out)"
  fi
}

helm dependency build "$CHART" >/dev/null

# --- the smoke service, as deployed ---------------------------------------------------------------------------
out=$(render -f "$SMOKE")
for kind in ServiceAccount Deployment Service PodDisruptionBudget NetworkPolicy; do
  [ "$(count "^kind: $kind$" "$out")" -eq 1 ] && ok "renders one $kind" || bad "renders one $kind"
done
[ "$(count '^kind: HorizontalPodAutoscaler$' "$out")" -eq 0 ] && ok "no HPA when autoscaling is disabled" || bad "no HPA when autoscaling is disabled"
has '^  replicas: 2$' "$out" && ok "fixed replica count used when autoscaling is disabled" || bad "fixed replica count used when autoscaling is disabled"
has 'image: ghcr.io/stefanprodan/podinfo@sha256:' "$out" && ok "image pinned by digest, not tag" || bad "image pinned by digest, not tag"

for rule in 'runAsNonRoot: true' 'readOnlyRootFilesystem: true' 'allowPrivilegeEscalation: false' 'privileged: false' \
            'drop: \["ALL"\]' 'type: RuntimeDefault' 'enableServiceLinks: false'; do
  has "$rule" "$out" && ok "hardening: $rule" || bad "hardening: $rule"
done
[ "$(count 'automountServiceAccountToken: false' "$out")" -eq 2 ] && ok "service account token not mounted (SA and pod)" || bad "service account token not mounted (SA and pod)"
has 'mountPath: /tmp' "$out" && ok "only /tmp is writable (emptyDir)" || bad "only /tmp is writable (emptyDir)"
has 'maxUnavailable: 0' "$out" && ok "rolling update never drops below desired replicas" || bad "rolling update never drops below desired replicas"
has 'topologyKey: topology.kubernetes.io/zone' "$out" && ok "replicas spread across zones" || bad "replicas spread across zones"
has 'policyTypes: \["Ingress"\]' "$out" && ok "network policy restricts ingress" || bad "network policy restricts ingress"
has 'unhealthyPodEvictionPolicy: AlwaysAllow' "$out" && ok "PDB never blocks drains on unhealthy pods" || bad "PDB never blocks drains on unhealthy pods"
has 'podAntiAffinity:' "$out" && ok "replicas prefer different nodes" || bad "replicas prefer different nodes"
for probe in startupProbe livenessProbe readinessProbe; do
  has "$probe:" "$out" && ok "has $probe" || bad "has $probe"
done

if kubeconform -strict -summary <<<"$out" >/tmp/kubeconform.out 2>&1; then ok "manifests valid against Kubernetes schemas"; else bad "manifests valid against Kubernetes schemas"; cat /tmp/kubeconform.out; fi
if kube-linter lint --config "$CHART/tests/kube-linter.yaml" - <<<"$out" >/tmp/kube-linter.out 2>&1; then
  ok "kube-linter: no findings"
else
  bad "kube-linter: no findings"; cat /tmp/kube-linter.out
fi

# --- options ----------------------------------------------------------------------------------------------------
out=$(render -f "$SMOKE" --set autoscaling.enabled=true --set autoscaling.minReplicas=3 --set autoscaling.maxReplicas=9)
[ "$(count '^kind: HorizontalPodAutoscaler$' "$out")" -eq 1 ] && ok "HPA rendered when autoscaling is enabled" || bad "HPA rendered when autoscaling is enabled"
has '^  replicas:' "$out" && bad "Deployment must not fix replicas under an HPA" || ok "Deployment leaves replicas to the HPA"

out=$(render -f "$SMOKE" --set 'envFromSecrets[0]=stripe')
has 'name: stripe' "$out" && has 'secretRef:' "$out" && ok "envFromSecrets loads a Kubernetes Secret" || bad "envFromSecrets loads a Kubernetes Secret"

out=$(render -f "$SMOKE" --set 'networkPolicy.allowFromNamespaces[0]=meterbook-staging')
has 'kubernetes.io/metadata.name: meterbook-staging' "$out" && ok "extra namespaces can be allowed explicitly" || bad "extra namespaces can be allowed explicitly"

out=$(render -f "$SMOKE" --set image.digest= --set image.tag=6.15.0)
has 'image: ghcr.io/stefanprodan/podinfo:6.15.0' "$out" && ok "tag used when no digest is set" || bad "tag used when no digest is set"

# --- guard rails --------------------------------------------------------------------------------------------------
expect_render_fails "requires an image repository" "image.repository is required"
expect_render_fails "rejects the latest tag" "must not be 'latest'" -f "$SMOKE" --set image.digest= --set image.tag=latest
expect_render_fails "rejects a malformed digest" "must start with sha256:" -f "$SMOKE" --set image.digest=abc123
expect_render_fails "rejects running as root" "non-root UID" -f "$SMOKE" --set securityContext.runAsUser=0
expect_render_fails "requires a memory limit" "limits.memory is required" -f "$SMOKE" --set resources.limits=null
expect_render_fails "rejects maxReplicas below minReplicas" "maxReplicas must be >= minReplicas" -f "$SMOKE" \
  --set autoscaling.enabled=true --set autoscaling.minReplicas=4 --set autoscaling.maxReplicas=2


# --- management port, writable paths, config files -------------------------------------------------------------
MGMT="$CHART/tests/management-values.yaml"
out=$(render -f "$MGMT")
has 'name: management' "$out" && has 'containerPort: 9000' "$out" && ok "declares the management port" || bad "declares the management port"
[ "$(count 'port: management' "$out")" -eq 3 ] && ok "all three probes use the management port" || bad "all three probes use the management port"
svc=$(awk '/^kind: Service$/,/^---/' <<<"$out")
! has '9000' "$svc" && ok "the Service does not expose the management port" || bad "the Service does not expose the management port"
has 'mountPath: /opt/keycloak/data$' "$out" && has 'sizeLimit: 256Mi' "$out" && ok "mounts a size-limited writable path" || bad "mounts a size-limited writable path"
has 'name: keycloak-realm' "$out" && has 'readOnly: true' "$out" && ok "mounts a ConfigMap read-only" || bad "mounts a ConfigMap read-only"
has 'readOnlyRootFilesystem: true' "$out" && ok "root filesystem stays read-only with extra mounts" || bad "root filesystem stays read-only with extra mounts"
out=$(render -f "$SMOKE")
[ "$(count 'port: http$' "$out")" -ge 3 ] && ! has 'name: management' "$out" && ok "probes default to the http port" || bad "probes default to the http port"
expect_render_fails "probes on management need a management port" "needs managementPort" -f "$MGMT" --set managementPort=0
expect_render_fails "rejects an unknown probe port" "must be http or management" -f "$MGMT" --set probes.port=admin
expect_render_fails "reserves the tmp volume name" "tmp is reserved" -f "$MGMT" --set 'writablePaths[0].name=tmp' --set 'writablePaths[0].path=/x'
expect_render_fails "requires DNS-label volume names" "must be a DNS label" -f "$MGMT" --set 'configFiles[0].name=Bad_Name'
expect_render_fails "requires absolute mount paths" "needs an absolute path" -f "$MGMT" --set 'writablePaths[0].path=relative'

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
