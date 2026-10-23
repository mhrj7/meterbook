variable "environments" {
  description = "Environments whose root Application runs on this cluster, e.g. [\"dev\", \"staging\"]."
  type        = list(string)

  validation {
    condition     = length(var.environments) > 0 && alltrue([for e in var.environments : contains(["dev", "staging", "prod"], e)])
    error_message = "environments must be a non-empty subset of dev, staging, prod."
  }

  validation {
    condition     = !(contains(var.environments, "prod") && length(var.environments) > 1)
    error_message = "prod gets its own cluster; it cannot share one with other environments."
  }
}

variable "argocd_chart_version" {
  description = "argo-cd Helm chart version (argo-helm)."
  type        = string
  default     = "10.11.0"
}

variable "argocd_apps_chart_version" {
  description = "argocd-apps Helm chart version (argo-helm)."
  type        = string
  default     = "2.0.7"
}

variable "reconciliation_timeout" {
  description = "How often ArgoCD re-reads Git when no webhook arrives."
  type        = string
  default     = "60s"
}
