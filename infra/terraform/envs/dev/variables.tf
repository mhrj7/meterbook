variable "region" {
  description = "AWS region."
  type        = string
  default     = "eu-north-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository (used for resource tags)."
  type        = string
}

variable "admin_cidrs" {
  description = "Public CIDRs (e.g. your office or home IP as x.x.x.x/32) allowed to reach the dev Kubernetes API."
  type        = list(string)
  # Personal IPs must not appear in plan output or CI logs.
  sensitive = true
}

variable "bootstrap_gitops" {
  description = "Install ArgoCD and the GitOps roots (needs network access to the cluster API from where Terraform runs)."
  type        = bool
  default     = true
}
