# dev: disposable and cheap — 2 AZs, one shared NAT gateway, spot nodes, single-AZ database, public Kubernetes API
# restricted to admin_cidrs. Destroy it when idle (see infra/terraform/README.md).

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial config: bucket, region, encrypt and use_lockfile come from backend.hcl (see backend.hcl.example).
  backend "s3" {
    key = "dev/terraform.tfstate"
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Product   = "meterbook"
      ManagedBy = "terraform"
      Repo      = var.github_repository
    }
  }
}

# Accepted risk (AWS-0040): dev exposes the Kubernetes API publicly so developers can reach it without a VPN.
# It is limited to admin_cidrs (0.0.0.0/0 is rejected by the cluster module) and prod keeps the API private.
#trivy:ignore:AWS-0040
module "stack" {
  source = "../../modules/stack"

  environment = "dev"
  disposable  = true

  az_count           = 2
  single_nat_gateway = true
  log_retention_days = 7

  endpoint_public_access       = true
  endpoint_public_access_cidrs = var.admin_cidrs

  node_instance_types = ["t4g.medium", "t4g.large"]
  node_capacity_type  = "SPOT"
  node_min_size       = 1
  node_max_size       = 3
  node_desired_size   = 2

  db_instance_class        = "db.t4g.micro"
  db_multi_az              = false
  db_backup_retention_days = 1
}
