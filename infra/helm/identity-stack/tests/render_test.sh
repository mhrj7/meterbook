#!/usr/bin/env bash
# ok/bad always return 0, so `cond && ok || bad` is a safe if/else in this file.
# shellcheck disable=SC2015
# Render tests for identity-stack (Keycloak + identity per environment, MB-T07).
#   infra/helm/identity-stack/tests/render_test.sh
set -euo pipefail

SOURCE="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SOURCE/../../.." && pwd)"
# Render from a clean copy, the way ArgoCD sees the repository: no previously built charts/ folders anywhere.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/infra/helm" "$WORK/infra/keycloak"
for chart in identity-stack meterbook-service meterbook-lib; do
  rsync -a --exclude charts/ --exclude Chart.lock "$ROOT/infra/helm/$chart" "$WORK/infra/helm/"
done
cp "$ROOT/infra/keycloak/realm-meterbook.json" "$WORK/infra/keycloak/"
CHART="$WORK/infra/helm/identity-stack"
pass=0
fail=0

ok()  { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }
render() { helm template identity "$CHART" "$@" 2>/dev/null; }
has() { grep -qE -- "$1" <<<"$2"; }
doc() { # doc <kind> <name> <rendered>: one manifest
  awk -v k="kind: $1" -v n="  name: $2" 'BEGIN{RS="\n---\n"} index($0, k"\n") && index($0, n"\n")' <<<"$3"
}
expect_render_fails() {
  local name=$1 message=$2; shift 2
  local out
  if out=$(helm template identity "$CHART" "$@" 2>&1); then
    bad "$name (rendered but should have failed)"
  elif grep -q -- "$message" <<<"$out"; then
    ok "$name"
  else
    bad "$name (failed for the wrong reason: $out)"
  fi
}

helm dependency build "$CHART" >/dev/null
for env in dev staging; do
  if ! helm template identity "$CHART" -f "$CHART/values-$env.yaml" >/dev/null 2>"$WORK/render-$env.err"; then
    echo "FAIL  renders from a clean checkout for $env (as ArgoCD does)"; grep -v 'symbolic link' "$WORK/render-$env.err"
    exit 1
  fi
  ok "renders from a clean checkout for $env (as ArgoCD does)"
done
DEV=$(render -f "$CHART/values-dev.yaml")
STAGING=$(render -f "$CHART/values-staging.yaml")

# --- realm -----------------------------------------------------------------------------------------------------
realm=$(helm template identity "$CHART" -f "$CHART/values-dev.yaml" --show-only templates/realm.yaml 2>/dev/null \
  | sed -n '/realm-meterbook.json: |-/,$p' | tail -n +2 | sed 's/^    //')
diff <(python3 -m json.tool <<<"$realm") <(python3 -m json.tool "$ROOT/infra/keycloak/realm-meterbook.json") >/dev/null \
  && ok "the realm ConfigMap is exactly infra/keycloak/realm-meterbook.json" || bad "the realm ConfigMap is exactly infra/keycloak/realm-meterbook.json"

# --- one database and role per environment ---------------------------------------------------------------------
has 'value: "keycloak_dev:keycloak_dev:KEYCLOAK_DB_PASSWORD identity_dev:identity_dev:IDENTITY_DB_PASSWORD"' "$DEV" \
  && ok "dev bootstraps keycloak_dev and identity_dev" || bad "dev bootstraps keycloak_dev and identity_dev"
has 'identity_staging' "$STAGING" && ! has 'identity_dev' "$STAGING" \
  && ok "staging uses its own databases" || bad "staging uses its own databases"
has 'key: meterbook-dev/database' "$STAGING" \
  && ok "staging reads the dev database location (shared cluster and instance)" || bad "staging reads the dev database location"
has 'key: meterbook-dev/database' "$DEV" && ok "dev reads its own database location" || bad "dev reads its own database location"
has 'jdbc:postgresql://\$\(DB_HOST\):\$\(DB_PORT\)/identity_dev\?sslmode=require' "$DEV" \
  && ok "identity connects to identity_dev over TLS" || bad "identity connects to identity_dev over TLS"
! grep -qE 'rds\.amazonaws\.com|arn:aws' <<<"$DEV$STAGING" \
  && ok "no hostnames or ARNs in the rendered manifests (they come from Secrets Manager)" || bad "no hostnames or ARNs in the rendered manifests"

# --- generated secrets -----------------------------------------------------------------------------------------
for name in keycloak-db identity-db keycloak-clients keycloak-admin; do
  es=$(doc ExternalSecret "$name" "$DEV")
  has 'refreshPolicy: CreatedOnce' "$es" && has 'kind: Password' "$es" \
    && ok "$name is generated once" || bad "$name is generated once"
done
es=$(doc ExternalSecret keycloak-clients "$DEV")
has 'target: MB_IDENTITY_CLIENT_SECRET' "$es" && has 'target: MB_OPERATOR_CLIENT_SECRET' "$es" \
  && ok "both Keycloak client secrets are generated" || bad "both Keycloak client secrets are generated"
pw=$(doc Password identity-stack "$DEV")
has 'length: 40' "$pw" && has 'symbols: 0' "$pw" && ok "passwords are 40 characters without symbols" || bad "passwords are 40 characters without symbols"

# --- ordering: secrets, then the bootstrap Job, then the workloads ---------------------------------------------
job=$(doc Job db-bootstrap "$DEV")
has 'argocd.argoproj.io/hook: Sync' "$job" && has 'sync-wave: "-1"' "$job" \
  && ok "db-bootstrap is a Sync hook in wave -1" || bad "db-bootstrap is a Sync hook in wave -1"
has 'sync-wave: "-2"' "$(doc ExternalSecret database "$DEV")" && ok "secrets sync in wave -2" || bad "secrets sync in wave -2"
has 'hook-delete-policy: BeforeHookCreation' "$job" && ok "a re-sync replaces the previous Job" || bad "a re-sync replaces the previous Job"

# --- hardening -------------------------------------------------------------------------------------------------
has 'runAsNonRoot: true' "$job" && has 'readOnlyRootFilesystem: true' "$job" && has 'allowPrivilegeEscalation: false' "$job" \
  && ok "the Job runs non-root on a read-only filesystem" || bad "the Job runs non-root on a read-only filesystem"
has 'serviceAccountName: db-bootstrap' "$job" && ok "the Job uses the db-bootstrap service account (Pod Identity)" || bad "the Job uses the db-bootstrap service account"
kc=$(doc Deployment keycloak "$DEV")
has 'port: management' "$kc" && has 'containerPort: 9000' "$kc" && has 'path: /health/ready' "$kc" \
  && ok "Keycloak is probed on its management port" || bad "Keycloak is probed on its management port"
has 'mountPath: /opt/keycloak/data/import' "$kc" && has 'readOnly: true' "$kc" \
  && ok "the realm is mounted read-only where Keycloak imports it" || bad "the realm is mounted read-only"
svc=$(doc Service keycloak "$DEV")$(doc Service identity "$DEV")
! has '9000|9090' "$svc" && ok "Services expose neither management port" || bad "Services expose neither management port"

# --- schemas and lint (custom resources are checked against the ESO CRDs in the GitOps end-to-end test) ----------
LINT_CONFIG="$ROOT/infra/helm/meterbook-service/tests/kube-linter.yaml"
if kubeconform -strict -summary -ignore-missing-schemas <<<"$DEV" >/tmp/identity-kubeconform.out 2>&1; then
  ok "manifests valid against Kubernetes schemas"
else
  bad "manifests valid against Kubernetes schemas"; cat /tmp/identity-kubeconform.out
fi
if kube-linter lint --config "$LINT_CONFIG" - <<<"$DEV" >/tmp/identity-kube-linter.out 2>&1; then
  ok "kube-linter: no findings"
else
  bad "kube-linter: no findings"; cat /tmp/identity-kube-linter.out
fi

# --- guard rails -----------------------------------------------------------------------------------------------
expect_render_fails "requires the environment" "environment is required" -f "$CHART/values-dev.yaml" --set environment=
expect_render_fails "rejects unknown environments" "must be dev, staging or prod" -f "$CHART/values-dev.yaml" --set environment=qa
expect_render_fails "requires a db-bootstrap digest" "must be a sha256 digest" -f "$CHART/values-dev.yaml" --set dbBootstrap.image.digest=latest
expect_render_fails "requires a Keycloak image" "set image.digest" -f "$CHART/values-dev.yaml" --set keycloak.image.digest=

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
