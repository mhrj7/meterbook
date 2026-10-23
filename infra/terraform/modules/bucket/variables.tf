variable "name" {
  description = "Globally unique bucket name."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.name))
    error_message = "Bucket name must be 3-63 characters of lowercase letters, digits, dots and hyphens."
  }
}

variable "kms_key_arn" {
  description = "KMS key for SSE-KMS. Null uses SSE-S3 (AES256)."
  type        = string
  default     = null
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep previous object versions before they expire."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_retention_days >= 1
    error_message = "Noncurrent versions must be kept for at least 1 day."
  }
}

variable "force_destroy" {
  description = "Allow Terraform to delete the bucket while it still contains objects. Never true in prod."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to the bucket."
  type        = map(string)
  default     = {}
}
