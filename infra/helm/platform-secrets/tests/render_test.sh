#!/usr/bin/env bash
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else in this file.
# shellcheck disable=SC2015
# Render tests for the platform-secrets chart. Requires helm and kubeconform on PATH.
#   infra/helm/platform-secrets/tests/render_test.sh
set -euo pipefail

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

for env in dev staging prod; do
  out=$(render -f "$CHART/values-$env.yaml")
  case $env in
    dev)     stores=1; keys=dev;  foreign=prod ;;
    staging) stores=0; keys=dev;  foreign=prod ;;   # staging runs on the dev cluster with dev AWS secrets
    prod)    stores=1; keys=prod; foreign=dev ;;
  esac

  [ "$(grep -c '^kind: ClusterSecretStore$' <<<"$out")" -eq "$stores" ] && ok "$env: $stores ClusterSecretStore" || bad "$env: $stores ClusterSecretStore"
  [ "$(grep -c '^kind: ExternalSecret$' <<<"$out")" -eq 2 ] && ok "$env: two ExternalSecrets" || bad "$env: two ExternalSecrets"
  [ "$(grep -c "namespace: meterbook-$env$" <<<"$out")" -eq 2 ] && ok "$env: ExternalSecrets in namespace meterbook-$env" || bad "$env: ExternalSecrets in namespace meterbook-$env"
  grep -q "key: meterbook-$keys/stripe-api-key" <<<"$out" && ok "$env: reads meterbook-$keys/* secrets" || bad "$env: reads meterbook-$keys/* secrets"
  if grep -q "meterbook-$foreign/" <<<"$out"; then
    bad "$env: references meterbook-$foreign secrets"
  else
    ok "$env: never references meterbook-$foreign secrets"
  fi
  if [ "$stores" -eq 1 ]; then
    grep -A3 'conditions:' <<<"$out" | grep -q "\"meterbook-$env\"" && ok "$env: store restricted to listed namespaces" || bad "$env: store restricted to listed namespaces"
    grep -q 'auth:' <<<"$out" && bad "$env: store must not embed credentials" || ok "$env: store embeds no credentials (Pod Identity)"
  fi

  if kubeconform -strict -summary -schema-location default -schema-location "$SCHEMAS" <<<"$out" >/tmp/kubeconform.out 2>&1; then
    ok "$env: manifests valid against ESO CRD schemas"
  else
    bad "$env: manifests valid against ESO CRD schemas"; cat /tmp/kubeconform.out
  fi
done

out=$(render -f "$CHART/values-dev.yaml")
grep -A4 'conditions:' <<<"$out" | grep -q '"meterbook-staging"' && ok "dev store also admits the staging namespace (shared cluster)" || bad "dev store also admits the staging namespace (shared cluster)"

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
