variable "region" {
  description = "AWS region."
  type        = string
  default     = "eu-north-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository (used for resource tags)."
  type        = string
}

variable "bootstrap_gitops" {
  description = "Install ArgoCD and the GitOps roots (needs network access to the cluster API from where Terraform runs)."
  type        = bool
  default     = false
}
