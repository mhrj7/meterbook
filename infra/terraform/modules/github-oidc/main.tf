# Lets GitHub Actions assume AWS roles with short-lived OIDC tokens, so CI never holds static AWS keys.
#   plan role:  read-only; usable from pull requests and main (drift checks)
#   apply role: full access; usable only from the listed GitHub environments, whose protection rules
#               (required reviewers, main-only) gate every apply

locals {
  issuer_host  = "token.actions.githubusercontent.com"
  provider_arn = var.create_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_provider_arn

  # GitHub's immutable subject format embeds owner and repository IDs (repo:owner@id/name@id:...), so a renamed or
  # re-created repository with the same name cannot assume these roles.
  subject_repo = var.github_subject_repository != null ? var.github_subject_repository : var.github_repository

  plan_subjects = [
    "repo:${local.subject_repo}:pull_request",
    "repo:${local.subject_repo}:ref:refs/heads/main",
  ]
  apply_subjects = [for env in var.apply_environments : "repo:${local.subject_repo}:environment:${env}"]

  trust_policies = {
    for role, subjects in { plan = local.plan_subjects, apply = local.apply_subjects } : role => jsonencode({
      Version = "2012-10-17"
      Statement = [{
        Sid       = "GitHubActionsOIDC"
        Effect    = "Allow"
        Principal = { Federated = local.provider_arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = { "${local.issuer_host}:aud" = "sts.amazonaws.com" }
          StringLike   = { "${local.issuer_host}:sub" = subjects }
        }
      }]
    })
  }
  plan_trust_policy  = local.trust_policies["plan"]
  apply_trust_policy = local.trust_policies["apply"]
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_provider ? 1 : 0

  url            = "https://${local.issuer_host}"
  client_id_list = ["sts.amazonaws.com"]
  tags           = var.tags
}

resource "aws_iam_role" "plan" {
  name                 = var.plan_role_name
  description          = "Terraform plan from GitHub Actions (${var.github_repository})"
  assume_role_policy   = local.plan_trust_policy
  max_session_duration = 3600
  tags                 = var.tags
}

resource "aws_iam_role_policy_attachment" "plan_read_only" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy" "plan_state_access" {
  name   = "terraform-state"
  role   = aws_iam_role.plan.id
  policy = local.plan_state_policy
}

resource "aws_iam_role" "apply" {
  name                 = var.apply_role_name
  description          = "Terraform apply from protected GitHub environments (${var.github_repository})"
  assume_role_policy   = local.apply_trust_policy
  max_session_duration = 3600
  tags                 = var.tags
}

resource "aws_iam_role_policy_attachment" "apply_admin" {
  role       = aws_iam_role.apply.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

locals {
  # Plan reads state and must write only the lock file (use_lockfile = true).
  plan_state_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListStateBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [var.state_bucket_arn]
      },
      {
        Sid      = "ReadState"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = ["${var.state_bucket_arn}/*"]
      },
      {
        Sid      = "ManageLockFileOnly"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject"]
        Resource = ["${var.state_bucket_arn}/*.tflock"]
      },
    ]
  })
}
