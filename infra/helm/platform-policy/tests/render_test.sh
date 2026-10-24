#!/usr/bin/env bash
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else in this file.
# shellcheck disable=SC2015
# Render tests for the platform-policy chart. Requires helm and kubeconform on PATH.
#   infra/helm/platform-policy/tests/render_test.sh
set -euo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
pass=0
fail=0

ok()  { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }

render() { helm template platform-policy "$CHART" "$@"; }

out=$(render -f "$CHART/values-dev.yaml")
kubeconform -strict -summary -schema-location default -schema-location "$SCHEMAS" <<<"$out" >/dev/null \
  && ok "dev: manifests valid (kubeconform, Istio and Sigstore CRD schemas)" || bad "dev: manifests valid (kubeconform, Istio and Sigstore CRD schemas)"
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
  grep -q "policy.sigstore.dev/include: \"true\"" <<<"$doc" && ok "$ns admits only signed images" || bad "$ns admits only signed images"
done
[ "$(grep -c '^kind: Namespace$' <<<"$out")" -eq 2 ] && ok "dev cluster: exactly two namespaces enrolled" \
  || bad "dev cluster: exactly two namespaces enrolled"
grep -qE 'name: (argocd|external-secrets|kube-system|istio-system)$' <<<"$(grep -A3 '^kind: Namespace$' <<<"$out")" \
  && bad "platform namespaces must stay outside the mesh" || ok "platform namespaces stay outside the mesh"

# --- image admission (ADR-0012) ------------------------------------------------------------------------------
out=$(render -f "$CHART/values-dev.yaml")
[ "$(grep -c '^kind: ClusterImagePolicy$' <<<"$out")" -eq 1 ] && ok "one image policy: our own images only" \
  || bad "one image policy: our own images only"
grep -q 'signatureFormat' <<<"$out" && bad "policies use the classic signature format (the controller cannot verify bundles yet)" \
  || ok "policies use the classic signature format (the controller cannot verify bundles yet)"
grep -qF 'glob: "ghcr.io/mhrj7/**"' <<<"$out" && grep -qF 'subjectRegExp: "^https://github\\.com/mhrj7/meterbook/\\.github/workflows/container\\.yml@refs/heads/main$"' <<<"$out" \
  && ok "our images must be signed by container.yml on main, exactly" || bad "our images must be signed by container.yml on main, exactly"
subjects=$(sed -n 's/^ *subjectRegExp: "\(.*\)"$/\1/p' <<<"$out")
[ -n "$subjects" ] && ! grep -vE '^\^https://github\\\\\.com/[^$]+\$$' <<<"$subjects" >/dev/null \
  && ok "every signer identity is anchored (^...$) to one GitHub repository" || bad "every signer identity is anchored (^...$) to one GitHub repository"
[ "$(grep -c 'issuer: https://token.actions.githubusercontent.com' <<<"$out")" -eq 1 ] \
  && ok "only GitHub Actions' OIDC issuer is trusted" || bad "only GitHub Actions' OIDC issuer is trusted"

out=$(render -f "$CHART/values-prod.yaml")
[ "$(grep -c '^kind: Namespace$' <<<"$out")" -eq 1 ] && grep -q 'name: meterbook-prod$' <<<"$out" \
  && ok "prod cluster: meterbook-prod alone enrolled" || bad "prod cluster: meterbook-prod alone enrolled"

out=$(render)
[ "$(grep -c '^kind: Namespace$' <<<"$out" || true)" -eq 0 ] && ok "defaults enrol nothing" || bad "defaults enrol nothing"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
