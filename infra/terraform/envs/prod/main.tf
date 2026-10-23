# prod: 3 AZs, NAT gateway per AZ, on-demand nodes, multi-AZ database with 14-day PITR, private Kubernetes API.
# Applied only by CI from the protected "production" GitHub environment.

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
    key = "prod/terraform.tfstate"
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

module "stack" {
  source = "../../modules/stack"

  environment = "prod"
  disposable  = false

  vpc_cidr           = "10.1.0.0/16"
  az_count           = 3
  single_nat_gateway = false
  log_retention_days = 90

  endpoint_public_access = false

  node_instance_types = ["m7g.large"]
  node_capacity_type  = "ON_DEMAND"
  node_min_size       = 3
  node_max_size       = 9
  node_desired_size   = 3

  db_instance_class        = "db.m7g.large"
  db_multi_az              = true
  db_backup_retention_days = 14
  db_performance_insights  = true
}

# Off until there is an in-VPC path to the private prod API (ADR-0006).
module "gitops" {
  source = "../../modules/gitops"
  count  = var.bootstrap_gitops ? 1 : 0

  environments = ["prod"]

  depends_on = [module.stack]
}
