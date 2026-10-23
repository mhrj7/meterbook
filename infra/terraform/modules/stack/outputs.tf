output "vpc_id" {
  description = "VPC ID."
  value       = module.network.vpc_id
}

output "cluster_name" {
  description = "EKS cluster name."
  value       = module.cluster.cluster_name
}

output "cluster_endpoint" {
  description = "Kubernetes API endpoint."
  value       = module.cluster.cluster_endpoint
}

output "database" {
  description = "Connection details. Credentials live in Secrets Manager, not here."
  value = {
    address    = module.postgres.address
    port       = module.postgres.port
    name       = module.postgres.database_name
    secret_arn = module.postgres.master_user_secret_arn
  }
}

output "buckets" {
  description = "Bucket names by purpose."
  value       = local.bucket_names
  depends_on  = [module.buckets]
}

output "ci_roles" {
  description = "Roles for GitHub Actions (set as repository variables)."
  value = {
    plan  = module.github_oidc.plan_role_arn
    apply = module.github_oidc.apply_role_arn
  }
}
