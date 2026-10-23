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
  secret_names               = var.app_secret_names
  extra_readable_secret_arns = compact([module.postgres.master_user_secret_arn])
  recovery_window_days       = var.disposable ? 7 : 30
  kms_deletion_window_days   = var.disposable ? 7 : 30
  tags                       = local.tags
}

module "github_oidc" {
  source = "../github-oidc"

  github_repository     = var.github_repository
  create_provider       = var.github_create_oidc_provider
  existing_provider_arn = var.github_existing_oidc_provider_arn
  apply_environments    = [var.github_apply_environment]
  state_bucket_arn      = "arn:aws:s3:::${var.state_bucket_name}"
  plan_role_name        = "${local.name}-terraform-plan"
  apply_role_name       = "${local.name}-terraform-apply"
  tags                  = local.tags
}
