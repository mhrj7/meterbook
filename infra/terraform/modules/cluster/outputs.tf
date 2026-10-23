output "cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "Kubernetes API endpoint."
  value       = module.eks.cluster_endpoint
}

output "cluster_certificate_authority_data" {
  description = "Base64 cluster CA certificate."
  value       = module.eks.cluster_certificate_authority_data
}

output "node_security_group_id" {
  description = "Security group shared by all nodes (used to grant database access)."
  value       = module.eks.node_security_group_id
}

output "oidc_provider_arn" {
  description = "Cluster OIDC provider ARN (IRSA)."
  value       = module.eks.oidc_provider_arn
}

output "api_access" {
  description = "Effective Kubernetes API exposure (exposed for tests and audits)."
  value = {
    private_endpoint = true
    public_endpoint  = var.endpoint_public_access
    public_cidrs     = local.public_access_cidrs
  }
}
