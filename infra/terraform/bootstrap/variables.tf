variable "region" {
  description = "AWS region for the state bucket."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Globally unique name for the Terraform state bucket, e.g. meterbook-tfstate-<account-id>."
  type        = string
}
