# Application secrets for one environment (ADR-0004):
#   - a dedicated, rotating KMS key
#   - empty secret shells named <name>/<secret>; values are set by an operator, never through Terraform
#   - an IAM role for External Secrets Operator, bound to its service account by EKS Pod Identity, that can read
#     exactly these secrets (plus any extra ARNs given, e.g. the RDS-managed database secret) and nothing else

data "aws_region" "current" {}

locals {
  secret_arns   = [for s in aws_secretsmanager_secret.this : s.arn]
  readable_arns = concat(local.secret_arns, var.extra_readable_secret_arns)
}

resource "aws_kms_key" "this" {
  description             = "${var.name} application secrets"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  tags                    = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}-app-secrets"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_secretsmanager_secret" "this" {
  for_each = toset(var.secret_names)

  name                    = "${var.name}/${each.value}"
  description             = "${var.name} ${each.value} (value set out-of-band, see docs/runbooks/secrets.md)"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = var.tags
}

resource "aws_iam_role" "eso" {
  name               = "${var.name}-external-secrets"
  description        = "External Secrets Operator in ${var.name} (EKS Pod Identity)"
  assume_role_policy = local.eso_trust_policy
  tags               = var.tags
}

resource "aws_iam_role_policy" "eso_read" {
  name   = "read-app-secrets"
  role   = aws_iam_role.eso.id
  policy = local.eso_read_policy
}

resource "aws_eks_pod_identity_association" "eso" {
  cluster_name    = var.cluster_name
  namespace       = var.eso_namespace
  service_account = var.eso_service_account
  role_arn        = aws_iam_role.eso.arn
  tags            = var.tags
}

locals {
  eso_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "EksPodIdentity"
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })

  eso_read_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadListedSecretsOnly"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
          "secretsmanager:ListSecretVersionIds",
          "secretsmanager:GetResourcePolicy",
        ]
        Resource = local.readable_arns
      },
      {
        Sid      = "DecryptViaSecretsManagerOnly"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.this.arn]
        Condition = {
          StringEquals = { "kms:ViaService" = "secretsmanager.${data.aws_region.current.region}.amazonaws.com" }
        }
      },
    ]
  })
}
