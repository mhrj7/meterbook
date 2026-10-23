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
}
