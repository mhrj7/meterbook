# Account bootstrap, owned by CI after the first run:
#   - the S3 bucket holding every environment's Terraform state (S3-native locking, no DynamoDB)
#   - the GitHub Actions OIDC provider and the two CI roles every environment uses:
#       meterbook-terraform-plan   read-only, assumable from pull requests and main
#       meterbook-terraform-apply  admin, assumable only from the protected GitHub environments
#
# Chicken-and-egg: CI needs a role before it can run this. scripts/cloudshell-bootstrap.sh creates the provider and
# the apply role once by hand; the import blocks below adopt them, so after the first run nothing is hand-managed.
# The first run uses local state and then migrates it into the bucket it created (see the bootstrap workflow).

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial config; bucket and region are passed at init. The first run overrides this with a local backend.
  backend "s3" {
    key = "bootstrap/terraform.tfstate"
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Product   = "meterbook"
      ManagedBy = "terraform"
      Component = "bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}

module "state_bucket" {
  source = "../modules/bucket"

  name = var.state_bucket_name

  # State history is the audit trail of every infrastructure change; keep a year of it.
  noncurrent_version_retention_days = 365
}

module "github_oidc" {
  source = "../modules/github-oidc"

  github_repository         = var.github_repository
  github_subject_repository = var.github_subject_repository
  create_provider           = true
  apply_environments        = ["dev", "production"]
  state_bucket_arn          = module.state_bucket.arn
  plan_role_name            = "meterbook-terraform-plan"
  apply_role_name           = "meterbook-terraform-apply"
  apply_max_session_seconds = 7200
}

import {
  to = module.github_oidc.aws_iam_openid_connect_provider.github[0]
  id = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

import {
  to = module.github_oidc.aws_iam_role.apply
  id = "meterbook-terraform-apply"
}

import {
  to = module.github_oidc.aws_iam_role_policy_attachment.apply_admin
  id = "meterbook-terraform-apply/arn:aws:iam::aws:policy/AdministratorAccess"
}
