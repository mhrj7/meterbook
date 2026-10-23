variable "name" {
  description = "Prefix for every secret and resource, e.g. meterbook-dev."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster where External Secrets Operator runs."
  type        = string
}

variable "secret_names" {
  description = "Application secrets to create as empty shells, e.g. stripe-api-key."
  type        = list(string)

  validation {
    condition     = alltrue([for s in var.secret_names : can(regex("^[a-z0-9][a-z0-9-]{1,62}$", s))])
    error_message = "Secret names must be lowercase letters, digits and hyphens (2-63 characters)."
  }

  validation {
    condition     = length(var.secret_names) == length(distinct(var.secret_names))
    error_message = "Secret names must be unique."
  }
}

variable "extra_readable_secret_arns" {
  description = "Other secrets ESO may read, e.g. the RDS-managed database secret."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for a in var.extra_readable_secret_arns :
      can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[^*?]+$", a))
    ])
    error_message = "extra_readable_secret_arns must be full Secrets Manager ARNs (no wildcards)."
  }
}

variable "recovery_window_days" {
  description = "Days a deleted secret can be restored (7-30)."
  type        = number
  default     = 30

  validation {
    condition     = var.recovery_window_days >= 7 && var.recovery_window_days <= 30
    error_message = "recovery_window_days must be 7-30; 0 (force delete) is not allowed."
  }
}

variable "kms_deletion_window_days" {
  description = "Waiting period before the KMS key is deleted (7-30)."
  type        = number
  default     = 30
}

variable "eso_namespace" {
  description = "Namespace of External Secrets Operator."
  type        = string
  default     = "external-secrets"
}

variable "eso_service_account" {
  description = "Service account of External Secrets Operator."
  type        = string
  default     = "external-secrets"
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
