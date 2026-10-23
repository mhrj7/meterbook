#!/usr/bin/env bash
# End-to-end test for secrets delivery (MB-T04, ADR-0004), against a disposable kind cluster:
#   Secrets Manager (moto) -> External Secrets Operator -> Kubernetes Secret -> running pod
# Proves: runtime delivery, rotation without redeploy, and namespace isolation of the ClusterSecretStore.
# Requires: kubectl, helm, aws CLI, and a current kube context pointing at a throwaway cluster.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
ESO_CHART_VERSION="${ESO_CHART_VERSION:-2.13.0}"
SECRET_NAME="meterbook-ci/stripe-api-key"
# Test-only values: moto accepts any credentials; these are not real keys.
export AWS_ACCESS_KEY_ID=testing AWS_SECRET_ACCESS_KEY=testing AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://127.0.0.1:5000"
START=$(date +%s)
pass=0

step() { echo; echo "=== $*"; }
ok()   { echo "PASS  $*"; pass=$((pass + 1)); }
die()  { echo "FAIL  $*"; exit 1; }
k8s_secret_value() {
  kubectl -n "$1" get secret "$2" -o jsonpath="{.data.$3}" 2>/dev/null | base64 -d 2>/dev/null || true
}
wait_for() { # wait_for <seconds> <description> <command...>
  local timeout=$1 what=$2; shift 2
  for _ in $(seq "$timeout"); do "$@" && return 0; sleep 1; done
  die "timed out after ${timeout}s waiting for: $what"
}

step "Start Secrets Manager stand-in (moto)"
kubectl apply -f "$HERE/moto.yaml"
kubectl -n moto rollout status deploy/moto --timeout=180s
kubectl -n moto port-forward svc/moto 5000:5000 >/tmp/moto-port-forward.log 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
wait_for 30 "moto port-forward" curl -sf "$ENDPOINT/moto-api/" -o /dev/null

step "Seed secret (value v1)"
V1="sk_test_e2e_v1_$RANDOM$RANDOM"
aws --endpoint-url "$ENDPOINT" secretsmanager create-secret --name "$SECRET_NAME" --secret-string "$V1" >/dev/null

step "Install External Secrets Operator $ESO_CHART_VERSION"
helm repo add external-secrets https://charts.external-secrets.io >/dev/null
helm upgrade --install external-secrets external-secrets/external-secrets \
  --version "$ESO_CHART_VERSION" --namespace external-secrets --create-namespace --wait --timeout 5m \
  --set 'extraEnv[0].name=AWS_SECRETSMANAGER_ENDPOINT' --set 'extraEnv[0].value=http://moto.moto.svc.cluster.local:5000' \
  --set 'extraEnv[1].name=AWS_ACCESS_KEY_ID' --set 'extraEnv[1].value=testing' \
  --set 'extraEnv[2].name=AWS_SECRET_ACCESS_KEY' --set 'extraEnv[2].value=testing'

step "Install platform-secrets chart"
kubectl create namespace meterbook --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace other --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install meterbook-secrets "$ROOT/infra/helm/platform-secrets" -f "$HERE/values-e2e.yaml" --wait

step "1. Secret is synced from Secrets Manager into Kubernetes"
kubectl -n meterbook wait --for=condition=Ready externalsecret/stripe --timeout=120s
[ "$(k8s_secret_value meterbook stripe STRIPE_API_KEY)" = "$V1" ] || die "Kubernetes Secret does not hold v1"
ok "ExternalSecret Ready and Kubernetes Secret holds the Secrets Manager value"

step "2. A running pod reads the secret at runtime"
kubectl apply -f "$HERE/consumer.yaml"
kubectl -n meterbook wait --for=condition=Ready pod/consumer --timeout=120s
wait_for 30 "consumer log line" sh -c "kubectl -n meterbook logs consumer | grep -qx 'key=$V1'"
ok "consumer pod received the secret through its environment"

step "3. Rotation reaches Kubernetes without a redeploy"
V2="sk_test_e2e_v2_$RANDOM$RANDOM"
ROTATE_AT=$(date +%s)
aws --endpoint-url "$ENDPOINT" secretsmanager put-secret-value --secret-id "$SECRET_NAME" --secret-string "$V2" >/dev/null
wait_for 60 "rotated value in Kubernetes" sh -c "[ \"\$(kubectl -n meterbook get secret stripe -o jsonpath='{.data.STRIPE_API_KEY}' | base64 -d)\" = '$V2' ]"
ok "rotated value synced in $(( $(date +%s) - ROTATE_AT ))s (refreshInterval 5s in this test, 1h in dev/prod)"

step "4. Namespaces outside the store's allow-list cannot read secrets"
kubectl apply -f "$HERE/forbidden-namespace.yaml"
sleep 15
status=$(kubectl -n other get externalsecret stolen -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')
[ "$status" != "True" ] || die "ExternalSecret in namespace 'other' became Ready"
kubectl -n other get secret stolen >/dev/null 2>&1 && die "Secret was created in namespace 'other'"
ok "ExternalSecret in namespace 'other' refused (Ready=${status:-unset}); no Secret created"

echo
echo "---"
echo "$pass passed in $(( $(date +%s) - START ))s"
