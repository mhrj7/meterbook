output "vpc_id" {
  description = "VPC ID."
  value       = module.vpc.vpc_id
}

output "azs" {
  description = "Availability zones in use."
  value       = local.azs
}

output "private_subnet_ids" {
  description = "Subnets for EKS nodes and pods."
  value       = module.vpc.private_subnets
}

output "public_subnet_ids" {
  description = "Subnets for internet-facing load balancers."
  value       = module.vpc.public_subnets
}

output "intra_subnet_ids" {
  description = "Subnets for EKS control-plane ENIs."
  value       = module.vpc.intra_subnets
}

output "database_subnet_group_name" {
  description = "RDS subnet group over the database subnets."
  value       = module.vpc.database_subnet_group_name
}

output "subnet_cidrs" {
  description = "Planned subnet CIDRs per tier (exposed for tests and docs)."
  value = {
    private  = local.private_subnets
    public   = local.public_subnets
    database = local.database_subnets
    intra    = local.intra_subnets
  }
}
