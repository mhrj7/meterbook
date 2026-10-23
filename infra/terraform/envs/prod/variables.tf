variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository that runs Terraform."
  type        = string
}

variable "state_bucket_name" {
  description = "State bucket created by bootstrap/ (same value as in backend.hcl)."
  type        = string
}

variable "github_existing_oidc_provider_arn" {
  description = "Set when prod shares an AWS account with dev (dev creates the provider). Leave null in a dedicated prod account."
  type        = string
  default     = null
}
