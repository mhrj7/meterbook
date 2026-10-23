variable "region" {
  description = "AWS region."
  type        = string
  default     = "eu-north-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository (used for resource tags)."
  type        = string
}
