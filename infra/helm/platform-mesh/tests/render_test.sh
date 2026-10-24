#!/usr/bin/env bash
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else in this file.
# shellcheck disable=SC2015
# Render tests for the platform-mesh chart. Requires helm and kubeconform on PATH.
#   infra/helm/platform-mesh/tests/render_test.sh
set -euo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
pass=0
fail=0

ok()  { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }

render() { helm template platform-mesh "$CHART" "$@"; }

out=$(render -f "$CHART/values-dev.yaml")
kubeconform -strict -summary -schema-location default -schema-location "$SCHEMAS" <<<"$out" >/dev/null \
  && ok "dev: manifests valid (kubeconform, Istio CRD schemas)" || bad "dev: manifests valid (kubeconform, Istio CRD schemas)"
grep -A12 '^kind: PeerAuthentication$' <<<"$out" | grep -q 'mode: STRICT' \
  && ok "mesh-wide PeerAuthentication is STRICT" || bad "mesh-wide PeerAuthentication is STRICT"
grep -A4 '^kind: PeerAuthentication$' <<<"$out" | grep -q 'namespace: istio-system' \
  && ok "PeerAuthentication lives in the root namespace (applies to the whole mesh)" \
  || bad "PeerAuthentication lives in the root namespace (applies to the whole mesh)"
grep -A12 '^kind: PeerAuthentication$' <<<"$out" | grep -q 'selector:' \
  && bad "PeerAuthentication must not select workloads (it would stop being mesh-wide)" \
  || ok "PeerAuthentication selects no workloads"

for ns in meterbook-dev meterbook-staging; do
  doc=$(awk -v want="$ns" '/^---/ {if (kind=="Namespace" && name==want) print buf; buf=""; kind=""; name=""; next}
    {buf=buf $0 "\n"} /^kind: / {kind=$2} /^  name: / && name=="" {name=$2}
    END {if (kind=="Namespace" && name==want) print buf}' <<<"$out")
  grep -q 'istio.io/dataplane-mode: ambient' <<<"$doc" && ok "$ns enrolled in ambient" || bad "$ns enrolled in ambient"
  grep -q 'Delete=false' <<<"$doc" && ok "$ns is never deleted by a prune" || bad "$ns is never deleted by a prune"
done
[ "$(grep -c '^kind: Namespace$' <<<"$out")" -eq 2 ] && ok "dev cluster: exactly two namespaces enrolled" \
  || bad "dev cluster: exactly two namespaces enrolled"
grep -qE 'name: (argocd|external-secrets|kube-system|istio-system)$' <<<"$(grep -A3 '^kind: Namespace$' <<<"$out")" \
  && bad "platform namespaces must stay outside the mesh" || ok "platform namespaces stay outside the mesh"

out=$(render -f "$CHART/values-prod.yaml")
[ "$(grep -c '^kind: Namespace$' <<<"$out")" -eq 1 ] && grep -q 'name: meterbook-prod$' <<<"$out" \
  && ok "prod cluster: meterbook-prod alone enrolled" || bad "prod cluster: meterbook-prod alone enrolled"

out=$(render)
[ "$(grep -c '^kind: Namespace$' <<<"$out" || true)" -eq 0 ] && ok "defaults enrol nothing" || bad "defaults enrol nothing"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
