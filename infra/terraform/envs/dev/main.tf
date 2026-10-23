# dev: disposable and cheap — 2 AZs, one shared NAT gateway, free-plan-eligible on-demand nodes, single-AZ database, public Kubernetes API
# restricted to admin_cidrs. Destroy it when idle (see infra/terraform/README.md).

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.3"
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

# Kubernetes access for the GitOps bootstrap: short-lived tokens from the CI role, which EKS maps to cluster-admin
# (cluster creator). Only reachable when the runner can reach the cluster API (see the apply workflow).
provider "helm" {
  kubernetes = {
    host                   = module.stack.cluster_endpoint
    cluster_ca_certificate = base64decode(module.stack.cluster_certificate_authority_data)
    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.stack.cluster_name, "--region", var.region]
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

  # On-demand and Free-plan eligible: new accounts get no Spot capacity, and accounts on the AWS Free plan may only
  # launch free-tier-eligible types (largest: m7i-flex.large, x86, 2 vCPU / 8 GB). Prod stays on Graviton.
  node_instance_types = ["m7i-flex.large"]
  node_ami_type       = "AL2023_x86_64_STANDARD"
  node_capacity_type  = "ON_DEMAND"
  node_min_size       = 1
  node_max_size       = 3
  node_desired_size   = 2

  db_instance_class        = "db.t4g.micro"
  db_multi_az              = false
  db_backup_retention_days = 1
}

# dev and staging share the dev cluster (ADR-0006).
module "gitops" {
  source = "../../modules/gitops"
  count  = var.bootstrap_gitops ? 1 : 0

  environments = ["dev", "staging"]

  depends_on = [module.stack]
}
