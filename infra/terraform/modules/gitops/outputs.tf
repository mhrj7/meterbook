output "root_applications" {
  description = "Root Applications installed on this cluster."
  value       = keys(local.roots_values.applications)
}

output "roots_values" {
  description = "Values passed to the argocd-apps chart (exposed for tests and audits)."
  value       = local.roots_values
}

output "argocd_chart_version" {
  description = "Installed argo-cd chart version."
  value       = helm_release.argocd.version
}
