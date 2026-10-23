variable "region" {
  description = "AWS region for the state bucket."
  type        = string
  default     = "eu-north-1"
}

variable "state_bucket_name" {
  description = "Globally unique name for the Terraform state bucket, e.g. meterbook-tfstate-<account-id>."
  type        = string
}

variable "github_repository" {
  description = "owner/name of the GitHub repository whose workflows may assume the CI roles."
  type        = string
}
