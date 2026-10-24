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

output "cluster_certificate_authority_data" {
  description = "Base64 cluster CA certificate (for the Helm/Kubernetes providers)."
  value       = module.cluster.cluster_certificate_authority_data
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

output "db_bootstrap" {
  description = "Role and policy of the db-bootstrap Job (exposed for tests and audits)."
  value = {
    role_arn   = aws_iam_role.db_bootstrap.arn
    policy     = aws_iam_role_policy.db_bootstrap.policy
    namespaces = sort([for a in aws_eks_pod_identity_association.db_bootstrap : a.namespace])
    secret     = module.app_secrets.secret_names["database"]
  }
}
