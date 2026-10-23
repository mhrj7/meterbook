# VPC with four subnet tiers per AZ:
#   public   — load balancers and NAT gateways only
#   private  — EKS nodes and pods (large /20s: the VPC CNI gives every pod a VPC IP)
#   database — RDS, no route to the internet
#   intra    — EKS control-plane ENIs, no route to the internet
# Flow logs go to CloudWatch for security investigations.

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # /16 -> three /20 private subnets (10.x.0.0, .16.0, .32.0), then /24s from 10.x.48.0 upward.
  private_subnets  = [for i in range(var.az_count) : cidrsubnet(var.cidr, 4, i)]
  public_subnets   = [for i in range(var.az_count) : cidrsubnet(var.cidr, 8, 48 + i)]
  database_subnets = [for i in range(var.az_count) : cidrsubnet(var.cidr, 8, 52 + i)]
  intra_subnets    = [for i in range(var.az_count) : cidrsubnet(var.cidr, 8, 56 + i)]
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = var.name
  cidr = var.cidr
  azs  = local.azs

  private_subnets  = local.private_subnets
  public_subnets   = local.public_subnets
  database_subnets = local.database_subnets
  intra_subnets    = local.intra_subnets

  create_database_subnet_group = true

  # Database subnets get their own route tables with no internet or NAT route (the module default would put them
  # on the private route tables, which egress through NAT).
  create_database_subnet_route_table     = true
  create_database_internet_gateway_route = false
  create_database_nat_gateway_route      = false

  enable_nat_gateway     = true
  single_nat_gateway     = var.single_nat_gateway
  one_nat_gateway_per_az = !var.single_nat_gateway

  enable_dns_hostnames = true
  enable_dns_support   = true

  # Lock down the default security group so nothing accidentally uses it.
  manage_default_security_group = true

  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_max_aggregation_interval               = 60
  flow_log_cloudwatch_log_group_retention_in_days = var.flow_log_retention_days

  # Tags the AWS Load Balancer Controller uses to discover subnets.
  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }

  tags = var.tags
}
