output "cluster_name" {
  description = "EKS cluster name (aws eks update-kubeconfig --name <this>)."
  value       = module.stack.cluster_name
}

output "database" {
  description = "Database connection details; credentials are in Secrets Manager."
  value       = module.stack.database
}

output "buckets" {
  description = "Bucket names by purpose."
  value       = module.stack.buckets
}


output "app_secrets" {
  description = "Secret names for ExternalSecrets and the ESO role (values are set out-of-band)."
  value       = module.stack.app_secrets
}

output "gitops_roots" {
  description = "ArgoCD root Applications on this cluster."
  value       = try(module.gitops[0].root_applications, [])
}
