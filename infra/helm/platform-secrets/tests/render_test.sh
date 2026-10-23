#!/usr/bin/env bash
# Render tests for the platform-secrets chart. Requires helm and kubeconform on PATH.
#   infra/helm/platform-secrets/tests/render_test.sh
set -euo pipefail
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else here.
# shellcheck disable=SC2015

CHART="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
pass=0
fail=0

ok()  { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }

render() { helm template meterbook-secrets "$CHART" "$@"; }

expect_render_fails() {
  local name=$1 message=$2; shift 2
  local out
  if out=$(render "$@" 2>&1); then
    bad "$name (rendered but should have failed)"
  elif grep -q "$message" <<<"$out"; then
    ok "$name"
  else
    bad "$name (failed for the wrong reason: $out)"
  fi
}

for env in dev prod; do
  out=$(render -f "$CHART/values-$env.yaml")

  [ "$(grep -c '^kind: ClusterSecretStore$' <<<"$out")" -eq 1 ] && ok "$env: one ClusterSecretStore" || bad "$env: one ClusterSecretStore"
  [ "$(grep -c '^kind: ExternalSecret$' <<<"$out")" -eq 2 ] && ok "$env: two ExternalSecrets" || bad "$env: two ExternalSecrets"
  grep -q "key: meterbook-$env/stripe-api-key" <<<"$out" && ok "$env: remote keys are environment-scoped" || bad "$env: remote keys are environment-scoped"
  if grep -q "meterbook-$([ "$env" = dev ] && echo prod || echo dev)/" <<<"$out"; then
    bad "$env: references another environment's secrets"
  else
    ok "$env: never references another environment's secrets"
  fi
  grep -A2 'conditions:' <<<"$out" | grep -q '"meterbook"' && ok "$env: store restricted to the meterbook namespace" || bad "$env: store restricted to the meterbook namespace"
  grep -q 'auth:' <<<"$out" && bad "$env: store must not embed credentials" || ok "$env: store embeds no credentials (Pod Identity)"

  if kubeconform -strict -summary -schema-location default -schema-location "$SCHEMAS" <<<"$out" >/tmp/kubeconform.out 2>&1; then
    ok "$env: manifests valid against ESO CRD schemas"
  else
    bad "$env: manifests valid against ESO CRD schemas"; cat /tmp/kubeconform.out
  fi
done

expect_render_fails "refuses an unrestricted store" "allowedNamespaces must list at least one namespace" \
  --set region=us-east-1
expect_render_fails "refuses ExternalSecrets outside allowed namespaces" "not in store.allowedNamespaces" \
  --set region=us-east-1 --set 'store.allowedNamespaces[0]=meterbook' \
  --set 'externalSecrets[0].name=x' --set 'externalSecrets[0].namespace=other' \
  --set 'externalSecrets[0].data[0].secretKey=K' --set 'externalSecrets[0].data[0].remoteKey=meterbook-dev/x'
expect_render_fails "requires a region" "region is required" \
  --set 'store.allowedNamespaces[0]=meterbook'
expect_render_fails "refuses an ExternalSecret without data" "has no data entries" \
  --set region=us-east-1 --set 'store.allowedNamespaces[0]=meterbook' \
  --set 'externalSecrets[0].name=x' --set 'externalSecrets[0].namespace=meterbook'

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
