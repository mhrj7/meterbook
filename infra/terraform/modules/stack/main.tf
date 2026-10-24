# One complete Meterbook environment. envs/dev and envs/prod call this with different sizing; every guard rail that
# separates "disposable" from "production" lives in the variable validations of this module.

data "aws_caller_identity" "current" {}

locals {
  name       = "meterbook-${var.environment}"
  account_id = data.aws_caller_identity.current.account_id
  tags       = merge(var.tags, { Environment = var.environment })

  # invoices: rendered invoice PDFs, retained for audits. exports: customer-requested data exports.
  bucket_names = { for p in ["invoices", "exports"] : p => "${local.name}-${p}-${local.account_id}" }
}

module "network" {
  source = "../network"

  name                    = local.name
  cidr                    = var.vpc_cidr
  az_count                = var.az_count
  single_nat_gateway      = var.single_nat_gateway
  flow_log_retention_days = var.log_retention_days
  tags                    = local.tags
}

module "cluster" {
  source = "../cluster"

  name                         = local.name
  kubernetes_version           = var.kubernetes_version
  vpc_id                       = module.network.vpc_id
  node_subnet_ids              = module.network.private_subnet_ids
  control_plane_subnet_ids     = module.network.intra_subnet_ids
  endpoint_public_access       = var.endpoint_public_access
  endpoint_public_access_cidrs = var.endpoint_public_access_cidrs
  node_instance_types          = var.node_instance_types
  node_ami_type                = var.node_ami_type
  node_capacity_type           = var.node_capacity_type
  node_min_size                = var.node_min_size
  node_max_size                = var.node_max_size
  node_desired_size            = var.node_desired_size
  log_retention_days           = var.log_retention_days
  deletion_protection          = !var.disposable
  tags                         = local.tags
}

module "postgres" {
  source = "../postgres"

  name                         = local.name
  vpc_id                       = module.network.vpc_id
  db_subnet_group_name         = module.network.database_subnet_group_name
  allowed_security_group_ids   = [module.cluster.node_security_group_id]
  instance_class               = var.db_instance_class
  multi_az                     = var.db_multi_az
  backup_retention_days        = var.db_backup_retention_days
  performance_insights_enabled = var.db_performance_insights
  deletion_protection          = !var.disposable
  skip_final_snapshot          = var.disposable
  tags                         = local.tags
}

module "buckets" {
  source   = "../bucket"
  for_each = local.bucket_names

  name          = each.value
  force_destroy = var.disposable
  tags          = merge(local.tags, { Purpose = each.key })
}

module "app_secrets" {
  source = "../app-secrets"

  name                       = local.name
  cluster_name               = module.cluster.cluster_name
  secret_names               = concat(var.app_secret_names, ["database"])
  extra_readable_secret_arns = compact([module.postgres.master_user_secret_arn])
  # Disposable envs are recreated with the same secret names, which a recovery window would block.
  recovery_window_days     = var.disposable ? 0 : 30
  kms_deletion_window_days = var.disposable ? 7 : 30
  tags                     = local.tags
}

# --- database bootstrap (MB-T07) -----------------------------------------------------------------------------------
# Services get their own database roles from a Job in the cluster (db-bootstrap), the only thing that ever reads the
# RDS master credentials. The "database" secret tells it where the database is and which secret holds those
# credentials: location metadata, not credentials, so unlike other app secrets its value is set here.

# not-a-secret: host, port and the ARN of the master secret; credentials stay with RDS (tests/stack.tftest.hcl).
resource "aws_secretsmanager_secret_version" "database" {
  secret_id = module.app_secrets.secret_arns["database"]
  secret_string = jsonencode({
    host              = module.postgres.address
    port              = tostring(module.postgres.port)
    master_secret_arn = module.postgres.master_user_secret_arn
  })
}

resource "aws_iam_role" "db_bootstrap" {
  name        = "${local.name}-db-bootstrap"
  description = "db-bootstrap Job in ${local.name}: reads the RDS master credentials (EKS Pod Identity)"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "EksPodIdentity"
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
  tags = local.tags
}

resource "aws_iam_role_policy" "db_bootstrap" {
  name = "read-master-credentials"
  role = aws_iam_role.db_bootstrap.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "ReadMasterCredentialsOnly"
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [module.postgres.master_user_secret_arn]
    }]
  })
}

resource "aws_eks_pod_identity_association" "db_bootstrap" {
  for_each = toset(var.app_namespaces)

  cluster_name    = module.cluster.cluster_name
  namespace       = each.value
  service_account = "db-bootstrap"
  role_arn        = aws_iam_role.db_bootstrap.arn
  tags            = local.tags
}
