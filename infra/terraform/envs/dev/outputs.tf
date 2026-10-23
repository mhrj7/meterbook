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

output "ci_roles" {
  description = "Set these as GitHub repository variables for the CI workflow."
  value       = module.stack.ci_roles
}
