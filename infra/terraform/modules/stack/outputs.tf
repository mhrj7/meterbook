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


output "app_secrets" {
  description = "Application secret names (remote keys for ExternalSecrets) and the ESO role."
  value = {
    names        = module.app_secrets.secret_names
    eso_role_arn = module.app_secrets.eso_role_arn
  }
}
