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

variable "admin_cidrs" {
  description = "Public CIDRs (e.g. your office or home IP as x.x.x.x/32) allowed to reach the dev Kubernetes API."
  type        = list(string)
}
