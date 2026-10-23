# EKS cluster for Meterbook services. Graviton (arm64) managed nodes in private subnets, control-plane ENIs in
# intra subnets, secrets envelope-encrypted with a dedicated KMS key, audit logging on.
# Pods get AWS permissions through EKS Pod Identity (the agent add-on is installed here).

locals {
  # CIDR allow-list only applies when the public endpoint is on; otherwise it is forced empty.
  public_access_cidrs = var.endpoint_public_access ? var.endpoint_public_access_cidrs : []
}

# Accepted risk (AWS-0104): nodes need outbound HTTPS to pull images and call AWS APIs through the NAT gateway.
# Follow-up: VPC endpoints for ECR/S3/STS plus egress filtering, tracked as a prod-hardening task.
#trivy:ignore:AWS-0104
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.29"

  name               = var.name
  kubernetes_version = var.kubernetes_version

  vpc_id                   = var.vpc_id
  subnet_ids               = var.node_subnet_ids
  control_plane_subnet_ids = var.control_plane_subnet_ids

  endpoint_private_access      = true
  endpoint_public_access       = var.endpoint_public_access
  endpoint_public_access_cidrs = local.public_access_cidrs

  # The identity that creates the cluster (the CI apply role) gets cluster-admin; humans get access entries later.
  enable_cluster_creator_admin_permissions = true
  authentication_mode                      = "API"

  enabled_log_types                      = ["api", "audit", "authenticator"]
  cloudwatch_log_group_retention_in_days = var.log_retention_days

  deletion_protection = var.deletion_protection

  addons = {
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
    kube-proxy             = {}
    coredns                = {}
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_ARM_64_STANDARD"
      instance_types = var.node_instance_types
      capacity_type  = var.node_capacity_type

      min_size     = var.node_min_size
      max_size     = var.node_max_size
      desired_size = var.node_desired_size

      labels = { "meterbook.io/pool" = "default" }
    }
  }

  tags = var.tags
}
