#!/usr/bin/env bash
# Verifies a freshly applied dev environment against the security and reliability baseline (MB-T03, MB-T04, MB-T06):
# AWS resources through AWS APIs, then GitOps, secrets delivery and workload hardening inside the cluster.
# Run with credentials for the account and network access to the dev cluster API (the apply workflow does both).
#   STATE_BUCKET=<bucket> AWS_REGION=eu-north-1 tests/infra/verify-dev.sh
set -euo pipefail

NAME="meterbook-dev"
REGION="${AWS_REGION:?AWS_REGION is required}"
STATE_BUCKET="${STATE_BUCKET:?STATE_BUCKET is required}"
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
pass=0
fail=0

ok()  { echo "PASS  $*"; pass=$((pass + 1)); }
bad() { echo "FAIL  $*"; fail=$((fail + 1)); }
check() { # check <description> <command...>: passes when the command exits 0
  local what=$1; shift
  if [ "$1" = eq ]; then # say what was found; hosts and addresses stay out of the public log
    if [ "$2" = "$3" ]; then ok "$what"; else
      bad "$what (got '$(printf %s "$2" | head -c 300 | tr '\n' ' ' | sed -E 's/[0-9]{1,3}(\.[0-9]{1,3}){3}/<ip>/g; s/[A-Za-z0-9.-]+\.amazonaws\.com/<aws-host>/g')', want '$3')"
    fi
  elif "$@" >/dev/null 2>&1; then ok "$what"; else bad "$what"; fi
}
eq() { [ "$1" = "$2" ]; }

echo "Region $REGION"

# --- Terraform state ------------------------------------------------------------------------------------------
check "state stored in s3://$STATE_BUCKET/dev/terraform.tfstate" \
  aws s3api head-object --bucket "$STATE_BUCKET" --key dev/terraform.tfstate
check "state bucket versioning enabled" \
  eq "$(aws s3api get-bucket-versioning --bucket "$STATE_BUCKET" --query Status --output text)" Enabled
if aws s3api head-object --bucket "$STATE_BUCKET" --key dev/terraform.tfstate.tflock >/dev/null 2>&1; then
  bad "no stale state lock left behind"
else
  ok "no stale state lock left behind"
fi

# --- Network --------------------------------------------------------------------------------------------------
VPC=$(aws ec2 describe-vpcs --filters "Name=tag:Name,Values=$NAME" --query 'Vpcs[0].VpcId' --output text)
check "VPC $VPC exists" test "$VPC" != None
check "VPC flow logs active" \
  eq "$(aws ec2 describe-flow-logs --filter "Name=resource-id,Values=$VPC" --query 'FlowLogs[0].FlowLogStatus' --output text)" ACTIVE
DB_RTS=$(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$VPC" "Name=tag:Name,Values=$NAME-db*" \
  --query 'RouteTables[].RouteTableId' --output text)
check "database subnets have dedicated route tables" test -n "$DB_RTS"
check "database route tables have no internet or NAT route" bash -c "
  for rt in $DB_RTS; do
    aws ec2 describe-route-tables --route-table-ids \$rt --query 'RouteTables[0].Routes[?DestinationCidrBlock==\`0.0.0.0/0\`]' --output text | grep -q . && exit 1
  done; exit 0"

# --- EKS ------------------------------------------------------------------------------------------------------
CLUSTER=$(aws eks describe-cluster --name "$NAME" --output json)
check "EKS cluster ACTIVE" eq "$(jq -r .cluster.status <<<"$CLUSTER")" ACTIVE
check "EKS private endpoint enabled" eq "$(jq -r .cluster.resourcesVpcConfig.endpointPrivateAccess <<<"$CLUSTER")" true
check "EKS public endpoint not open to 0.0.0.0/0" \
  bash -c "! jq -e '.cluster.resourcesVpcConfig.publicAccessCidrs | index(\"0.0.0.0/0\")' <<<'$CLUSTER'"
check "EKS secrets envelope-encrypted with KMS" \
  jq -e '.cluster.encryptionConfig[0].resources | index("secrets")' <<<"$CLUSTER"
check "EKS audit logging enabled" \
  jq -e '[.cluster.logging.clusterLogging[] | select(.enabled) | .types[]] | index("audit")' <<<"$CLUSTER"
NG=$(aws eks list-nodegroups --cluster-name "$NAME" --query 'nodegroups[0]' --output text)
check "node group $NG ACTIVE" \
  eq "$(aws eks describe-nodegroup --cluster-name "$NAME" --nodegroup-name "$NG" --query nodegroup.status --output text)" ACTIVE
RUNNING=$(aws ec2 describe-instances --filters "Name=tag:eks:cluster-name,Values=$NAME" Name=instance-state-name,Values=running \
  --query 'length(Reservations[].Instances[])' --output text)
check "worker nodes running ($RUNNING)" test "$RUNNING" -ge 1
AMI_TYPE=$(aws eks describe-nodegroup --cluster-name "$NAME" --nodegroup-name "$NG" --query nodegroup.amiType --output text)
WANT_ARCH=$([[ "$AMI_TYPE" == *ARM_64* ]] && echo arm64 || echo x86_64)
check "worker nodes are $WANT_ARCH, matching node group AMI $AMI_TYPE" bash -c "
  aws ec2 describe-instances --filters Name=tag:eks:cluster-name,Values=$NAME Name=instance-state-name,Values=running \
    --query 'Reservations[].Instances[].Architecture' --output text | tr '\t' '\n' | grep -vqx $WANT_ARCH && exit 1 || exit 0"
check "VPC CNI enforces NetworkPolicies" bash -c \
  "aws eks describe-addon --cluster-name $NAME --addon-name vpc-cni --query addon.configurationValues --output text | grep -q enableNetworkPolicy"
check "pod identity agent add-on ACTIVE" \
  eq "$(aws eks describe-addon --cluster-name "$NAME" --addon-name eks-pod-identity-agent --query addon.status --output text)" ACTIVE

# --- PostgreSQL -----------------------------------------------------------------------------------------------
DB=$(aws rds describe-db-instances --db-instance-identifier "$NAME" --output json)
check "RDS instance available" eq "$(jq -r '.DBInstances[0].DBInstanceStatus' <<<"$DB")" available
check "RDS not publicly accessible" eq "$(jq -r '.DBInstances[0].PubliclyAccessible' <<<"$DB")" false
check "RDS storage encrypted" eq "$(jq -r '.DBInstances[0].StorageEncrypted' <<<"$DB")" true
check "RDS master password managed in Secrets Manager" jq -e '.DBInstances[0].MasterUserSecret.SecretArn' <<<"$DB"
check "RDS backups enabled" test "$(jq -r '.DBInstances[0].BackupRetentionPeriod' <<<"$DB")" -ge 1
PG=$(jq -r '.DBInstances[0].DBParameterGroups[0].DBParameterGroupName' <<<"$DB")
check "RDS enforces TLS (rds.force_ssl=1)" \
  eq "$(aws rds describe-db-parameters --db-parameter-group-name "$PG" --query "Parameters[?ParameterName=='rds.force_ssl'].ParameterValue" --output text)" 1

# --- Buckets --------------------------------------------------------------------------------------------------
for purpose in invoices exports; do
  B="$NAME-$purpose-$ACCOUNT"
  check "bucket $purpose: all public access blocked" bash -c \
    "aws s3api get-public-access-block --bucket $B --query 'PublicAccessBlockConfiguration.[BlockPublicAcls,BlockPublicPolicy,IgnorePublicAcls,RestrictPublicBuckets]' --output text | grep -qx \$'True\tTrue\tTrue\tTrue'"
  check "bucket $purpose: versioning enabled" eq "$(aws s3api get-bucket-versioning --bucket "$B" --query Status --output text)" Enabled
  check "bucket $purpose: plain HTTP denied by policy" bash -c \
    "aws s3api get-bucket-policy --bucket $B --query Policy --output text | jq -e '.Statement[] | select(.Effect==\"Deny\" and .Condition.Bool[\"aws:SecureTransport\"]==\"false\")'"
done

# --- Application secrets and ESO access (MB-T04) --------------------------------------------------------------
for s in stripe-api-key stripe-webhook-signing-secret identity-oidc-client-secret; do
  check "secret $NAME/$s exists and uses the app KMS key" bash -c \
    "aws secretsmanager describe-secret --secret-id $NAME/$s --query KmsKeyId --output text | grep -q 'arn:aws:kms'"
done
check "ESO bound to its service account by Pod Identity" bash -c \
  "aws eks list-pod-identity-associations --cluster-name $NAME --namespace external-secrets --service-account external-secrets --query 'length(associations)' --output text | grep -qx 1"
ESO_ROLE="arn:aws:iam::$ACCOUNT:role/$NAME-external-secrets"
STRIPE_ARN=$(aws secretsmanager describe-secret --secret-id "$NAME/stripe-api-key" --query ARN --output text)
check "ESO role may read an app secret" eq "$(aws iam simulate-principal-policy --policy-source-arn "$ESO_ROLE" \
  --action-names secretsmanager:GetSecretValue --resource-arns "$STRIPE_ARN" --query 'EvaluationResults[0].EvalDecision' --output text)" allowed
check "ESO role may not read other secrets" bash -c "aws iam simulate-principal-policy --policy-source-arn $ESO_ROLE \
  --action-names secretsmanager:GetSecretValue --resource-arns arn:aws:secretsmanager:$REGION:$ACCOUNT:secret:someone-else-AbCdEf \
  --query 'EvaluationResults[0].EvalDecision' --output text | grep -qv allowed"
check "ESO role may not write secrets" bash -c "aws iam simulate-principal-policy --policy-source-arn $ESO_ROLE \
  --action-names secretsmanager:PutSecretValue --resource-arns $STRIPE_ARN \
  --query 'EvaluationResults[0].EvalDecision' --output text | grep -qv allowed"

# --- GitOps (MB-T06) and in-cluster secrets delivery (MB-T04) ---------------------------------------------------
aws eks update-kubeconfig --name "$NAME" --region "$REGION" >/dev/null

wait_until() { # wait_until <seconds> <command...>
  local deadline=$((SECONDS + $1)); shift
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -ge "$deadline" ] && return 1
    sleep 10
  done
}
app_field() { kubectl -n argocd get application "$1" -o "jsonpath={$2}" 2>/dev/null; }
app_synced() { [ "$(app_field "$1" .status.sync.status)" = Synced ]; }
app_healthy() { [ "$(app_field "$1" .status.health.status)" = Healthy ]; }
store_ready() {
  [ "$(kubectl get clustersecretstore aws-secrets-manager -o 'jsonpath={.status.conditions[?(@.type=="Ready")].status}')" = True ]
}
k8s_secret() { kubectl -n "$1" get secret "$2" -o "jsonpath={.data.$3}" 2>/dev/null | base64 -d 2>/dev/null; }
secret_is() { [ "$(k8s_secret "$1" "$2" "$3")" = "$4" ]; }
deploy_ready() { [ "$(kubectl -n "$1" get deploy "$2" -o 'jsonpath={.status.availableReplicas}')" = "$3" ]; }

check "ArgoCD server and controllers running" kubectl -n argocd rollout status deploy/argocd-server --timeout=300s

for app in meterbook-dev-root meterbook-staging-root platform-external-secrets platform-secrets-dev platform-secrets-staging smoke-dev smoke-staging; do
  if wait_until 900 app_synced "$app"; then ok "ArgoCD application $app Synced"; else bad "ArgoCD application $app Synced ($(app_field "$app" .status.sync.status))"; fi
done
for app in meterbook-dev-root meterbook-staging-root platform-external-secrets smoke-dev smoke-staging; do
  if wait_until 600 app_healthy "$app"; then ok "ArgoCD application $app Healthy"; else bad "ArgoCD application $app Healthy ($(app_field "$app" .status.health.status))"; fi
done

if wait_until 300 store_ready; then
  ok "ClusterSecretStore Ready: ESO authenticates to Secrets Manager through Pod Identity"
else
  bad "ClusterSecretStore Ready: ESO authenticates to Secrets Manager through Pod Identity"
fi

# Write test values into the (empty) dev secret shells, then expect them in both namespaces on the dev cluster.
RUN_ID="verify-$(date +%s)-$RANDOM"
for s in stripe-api-key stripe-webhook-signing-secret identity-oidc-client-secret; do
  aws secretsmanager put-secret-value --secret-id "$NAME/$s" --secret-string "$RUN_ID-$s" >/dev/null
done
for ns in meterbook-dev meterbook-staging; do
  kubectl -n "$ns" annotate externalsecret --all force-sync="$(date +%s)" --overwrite >/dev/null
done
for ns in meterbook-dev meterbook-staging; do
  if wait_until 180 secret_is "$ns" stripe STRIPE_API_KEY "$RUN_ID-stripe-api-key"; then
    ok "Secrets Manager value delivered to Kubernetes Secret in $ns"
  else
    bad "Secrets Manager value delivered to Kubernetes Secret in $ns"
  fi
done
for app in platform-secrets-dev platform-secrets-staging; do
  if wait_until 300 app_healthy "$app"; then ok "ArgoCD application $app Healthy"; else bad "ArgoCD application $app Healthy ($(app_field "$app" .status.health.status))"; fi
done

kubectl -n meterbook-dev apply -f - >/dev/null <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: secret-reader
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 65534
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: reader
      image: busybox:1.37
      command: ["sh", "-c", "echo \"key=${STRIPE_API_KEY}\""]
      envFrom:
        - secretRef:
            name: stripe
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
YAML
reader_done() { [ "$(kubectl -n meterbook-dev get pod secret-reader -o 'jsonpath={.status.phase}')" = Succeeded ]; }
if wait_until 180 reader_done && kubectl -n meterbook-dev logs secret-reader | grep -qx "key=$RUN_ID-stripe-api-key"; then
  ok "a pod reads the AWS-managed secret at runtime"
else
  bad "a pod reads the AWS-managed secret at runtime"
fi

for ns in meterbook-dev meterbook-staging; do
  if wait_until 300 deploy_ready "$ns" smoke 2; then ok "smoke service 2/2 available in $ns"; else bad "smoke service 2/2 available in $ns"; fi
done
check "smoke pods run as non-root with a read-only root filesystem" bash -c "
  kubectl -n meterbook-dev get pods -l app.kubernetes.io/name=smoke -o jsonpath='{range .items[*]}{.spec.securityContext.runAsNonRoot} {.spec.containers[0].securityContext.readOnlyRootFilesystem}{\"\\n\"}{end}' \
    | grep -v '^true true$' | grep -q . && exit 1 || exit 0"

probe() { # probe <namespace> <pod-name>: HTTP GET to the dev smoke service from a pod in <namespace>
  kubectl -n "$1" run "$2" --image=busybox:1.37 --restart=Never --rm -i --quiet \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}}}}' \
    -- wget -q -T 5 -O- http://smoke.meterbook-dev.svc.cluster.local/healthz
}
if probe meterbook-dev np-allowed >/dev/null 2>&1; then
  ok "NetworkPolicy: same-namespace traffic to the smoke service is allowed"
else
  bad "NetworkPolicy: same-namespace traffic to the smoke service is allowed"
fi
kubectl create namespace np-outsider --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if probe np-outsider np-denied >/dev/null 2>&1; then
  bad "NetworkPolicy: traffic from another namespace is blocked"
else
  ok "NetworkPolicy: traffic from another namespace is blocked"
fi

# --- Identity on EKS (MB-T07) ------------------------------------------------------------------------------------
# Keycloak and identity per environment, deployed by ArgoCD: generated secrets, the db-bootstrap Job, the workloads.
# The API is exercised from inside the cluster (no public ingress yet), with the same image the bootstrap uses.
# The tools image is the db-bootstrap image pinned for dev (psql, aws, curl, jq), so it exists even if the Job does not.
TOOLS_DIGEST=$(awk '/^dbBootstrap:/{f=1} f && /digest:/ {gsub(/"/, "", $2); print $2; exit}' \
  "$(dirname "$0")/../../infra/helm/identity-stack/values-dev.yaml")
TOOLS_IMAGE="ghcr.io/mhrj7/meterbook-db-bootstrap@$TOOLS_DIGEST"
job_done() { [ "$(kubectl -n "$1" get job db-bootstrap -o 'jsonpath={.status.succeeded}')" = 1 ]; }

for env in dev staging; do
  ns="meterbook-$env"
  if wait_until 900 app_synced "identity-$env"; then ok "ArgoCD application identity-$env Synced"; else bad "ArgoCD application identity-$env Synced ($(app_field "identity-$env" .status.sync.status))"; fi
  if wait_until 900 app_healthy "identity-$env"; then ok "ArgoCD application identity-$env Healthy"; else bad "ArgoCD application identity-$env Healthy ($(app_field "identity-$env" .status.health.status))"; fi
  if wait_until 120 job_done "$ns"; then ok "db-bootstrap created the $env databases and roles"; else bad "db-bootstrap created the $env databases and roles"; fi
  for d in keycloak identity; do
    if wait_until 300 deploy_ready "$ns" "$d" 1; then ok "$d available in $ns"; else bad "$d available in $ns"; fi
  done
  check "identity and keycloak pods run as non-root with a read-only root filesystem in $ns" bash -c "
    kubectl -n $ns get pods -l 'app.kubernetes.io/name in (keycloak,identity)' -o jsonpath='{range .items[*]}{.spec.securityContext.runAsNonRoot} {.spec.containers[0].securityContext.readOnlyRootFilesystem}{\"\\n\"}{end}' \
      | grep -v '^true true$' | grep -q . && exit 1 || exit 0"
done

in_cluster() { # in_cluster <name> <namespace> <env-json> <script>: run a script in the tools image, print its output
  kubectl -n "$2" run "$1" --image="$TOOLS_IMAGE" --restart=Never --rm -i --quiet --command \
    --overrides="{\"spec\":{\"securityContext\":{\"runAsNonRoot\":true,\"runAsUser\":10001,\"seccompProfile\":{\"type\":\"RuntimeDefault\"}},\"containers\":[{\"name\":\"$1\",\"image\":\"$TOOLS_IMAGE\",\"command\":[\"bash\",\"-c\",$(jq -Rs . <<<"$4")],\"env\":$3,\"securityContext\":{\"allowPrivilegeEscalation\":false,\"capabilities\":{\"drop\":[\"ALL\"]}}}]}}" \
    -- true 2>/dev/null || true
}
secret_env() { # secret_env <var> <secret> <key>
  printf '{"name":"%s","valueFrom":{"secretKeyRef":{"name":"%s","key":"%s"}}}' "$1" "$2" "$3"
}

# The API: an operator token from Keycloak, then tenants through the identity service. The scripts below run in the
# pod, so their variables expand there (single quotes are intended).
SLUG="verify-$(date +%s)"
# shellcheck disable=SC2016
api=$(in_cluster identity-api meterbook-dev "[$(secret_env OPERATOR_SECRET keycloak-clients MB_OPERATOR_CLIENT_SECRET),{\"name\":\"SLUG\",\"value\":\"$SLUG\"}]" '
  set -u
  kc=http://keycloak.meterbook-dev.svc.cluster.local
  id=http://identity.meterbook-dev.svc.cluster.local
  token=$(curl -sf "$kc/realms/meterbook/protocol/openid-connect/token" -d grant_type=client_credentials \
    -d client_id=meterbook-operator --data-urlencode "client_secret=$OPERATOR_SECRET" | jq -r .access_token)
  echo "anonymous_me=$(curl -s -o /dev/null -w "%{http_code}" "$id/v1/me")"
  echo "operator_me=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $token" "$id/v1/me")"
  created=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $token" -H "Idempotency-Key: $SLUG" \
    -H "Content-Type: application/json" "$id/v1/tenants" \
    -d "$(jq -cn --arg s "$SLUG" "{name: \"Verify\", slug: \$s, domains: [\"\(\$s).test\"], initial_admin_email: \"admin@\(\$s).test\"}")")
  echo "create_tenant=$(tail -n1 <<<"$created")"
  tenant=$(head -n1 <<<"$created" | jq -r .id)
  echo "tenant_id=$tenant"
  echo "get_tenant=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $token" "$id/v1/tenants/$tenant")"
')
# kubectl run -i can deliver a pod's output twice (attach, then logs), so each key is read from its first line only.
field() { sed -n "s/^$1=//p" <<<"$api" | head -n1; }
check "identity rejects a request without a token (401)" eq "$(field anonymous_me)" 401
check "an operator token from Keycloak is accepted; /v1/me needs a tenant (403)" eq "$(field operator_me)" 403
check "an operator creates a tenant through identity, which provisions Keycloak (201)" eq "$(field create_tenant)" 201
check "the new tenant is readable (200)" eq "$(field get_tenant)" 200

# The database on RDS: identity's role is confined to its own database, its migrations ran there, and row-level
# security (ADR-0009) holds for that role: without a tenant scope it sees none of the tenant created above.
# shellcheck disable=SC2016
db=$(in_cluster identity-db meterbook-dev "[$(secret_env DB_HOST database DB_HOST),$(secret_env DB_PORT database DB_PORT),$(secret_env PGPASSWORD identity-db password),{\"name\":\"TENANT\",\"value\":\"$(field tenant_id)\"}]" '
  export PGHOST=$DB_HOST PGPORT=$DB_PORT PGUSER=identity_dev PGSSLMODE=require PGCONNECT_TIMEOUT=10 PGDATABASE=identity_dev
  q() { psql -qtAc "$1" 2>&1 | tail -n1; }
  echo "own=$(q "select count(*) from identity.flyway_schema_history where success")"
  echo "other=$(psql -d keycloak_dev -qtAc "select 1" 2>&1 | grep -o "does not have CONNECT privilege" | head -n1)"
  echo "bypass=$(q "select rolsuper or rolbypassrls from pg_roles where rolname = current_user")"
  echo "unforced=$(q "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = '\''identity'\'' and c.relkind = '\''r'\''
      and c.relname not in ('\''flyway_schema_history'\'', '\''idempotency_keys'\'')
      and not (c.relrowsecurity and c.relforcerowsecurity)")"
  echo "unscoped=$(q "select count(*) from identity.tenants")"
  echo "unscoped_insert=$(psql -qtAc "insert into identity.projects values (gen_random_uuid(), '\''$TENANT'\'', '\''x'\'', '\''x'\'', now())" 2>&1 | grep -o "row-level security" | head -n1)"
  echo "scoped=$(psql -qtA -c "begin" -c "select set_config('\''app.tenant_id'\'', '\''$TENANT'\'', true)" \
    -c "select count(*) from identity.tenants" -c "commit" 2>&1 | sed -n 2p)"
')
dbf() { sed -n "s/^$1=//p" <<<"$db" | head -n1; }
check "identity's migrations ran in identity_dev on RDS" test "$(dbf own)" -ge 5
check "identity's database role cannot connect to Keycloak's database" eq "$(dbf other)" "does not have CONNECT privilege"
check "identity's database role is no superuser and cannot bypass row-level security" eq "$(dbf bypass)" f
check "every identity table holding tenant data forces row-level security" eq "$(dbf unforced)" 0
check "without a tenant scope the new tenant is invisible (fails closed)" eq "$(dbf unscoped)" 0
check "without a tenant scope an insert is refused by row-level security" eq "$(dbf unscoped_insert)" "row-level security"
check "in the tenant's own scope the tenant is visible" eq "$(dbf scoped)" 1

echo
echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
