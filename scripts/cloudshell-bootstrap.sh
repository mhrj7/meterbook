#!/usr/bin/env bash
# One-time, per AWS account: run in AWS CloudShell as an administrator.
# Creates the GitHub OIDC provider and the CI apply role so GitHub Actions can run the bootstrap workflow;
# that workflow then adopts both into Terraform (infra/terraform/bootstrap, import blocks). Safe to re-run.
#   bash cloudshell-bootstrap.sh <owner>@<owner-id>/<repo>@<repo-id>
# The argument is the repository part of GitHub's immutable OIDC subject; find it with:
#   gh api repos/<owner>/<repo>/actions/oidc/customization/sub   (sub_claim_prefix without the "repo:")
set -euo pipefail

REPO="${1:?usage: cloudshell-bootstrap.sh <owner>@<owner-id>/<repo>@<repo-id>}"
ROLE="meterbook-terraform-apply"
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
PROVIDER_ARN="arn:aws:iam::${ACCOUNT}:oidc-provider/token.actions.githubusercontent.com"

if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$PROVIDER_ARN" >/dev/null 2>&1; then
  echo "OIDC provider exists: $PROVIDER_ARN"
else
  aws iam create-open-id-connect-provider \
    --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com >/dev/null
  echo "Created OIDC provider: $PROVIDER_ARN"
fi

TRUST=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "GitHubActionsOIDC",
    "Effect": "Allow",
    "Principal": {"Federated": "${PROVIDER_ARN}"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com"},
      "StringLike": {"token.actions.githubusercontent.com:sub": [
        "repo:${REPO}:environment:dev",
        "repo:${REPO}:environment:production"
      ]}
    }
  }]
}
JSON
)

if aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE" --policy-document "$TRUST"
  echo "Updated trust policy of $ROLE"
else
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$TRUST" \
    --description "Terraform apply from protected GitHub environments (${REPO})" --max-session-duration 3600 >/dev/null
  echo "Created role $ROLE"
fi
aws iam attach-role-policy --role-name "$ROLE" --policy-arn arn:aws:iam::aws:policy/AdministratorAccess

echo
echo "Set GitHub repository variable TF_APPLY_ROLE=arn:aws:iam::${ACCOUNT}:role/${ROLE}"
