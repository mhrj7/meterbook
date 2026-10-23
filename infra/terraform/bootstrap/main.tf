# One-time bootstrap: creates the S3 bucket that holds every environment's Terraform state.
# Run once per AWS account with local state, by an administrator:
#   terraform init && terraform apply -var state_bucket_name=<unique-name>
# Locking uses S3 conditional writes (use_lockfile = true in each backend), so no DynamoDB table is needed.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Product   = "meterbook"
      ManagedBy = "terraform"
      Component = "terraform-state"
    }
  }
}

module "state_bucket" {
  source = "../modules/bucket"

  name = var.state_bucket_name

  # State history is the audit trail of every infrastructure change; keep a year of it.
  noncurrent_version_retention_days = 365
}
