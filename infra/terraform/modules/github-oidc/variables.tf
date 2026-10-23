variable "github_repository" {
  description = "GitHub repository allowed to assume the roles, as owner/name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name with no wildcards."
  }
}

variable "github_subject_repository" {
  description = "Repository part of the OIDC sub claim when GitHub issues immutable subjects, e.g. owner@123/name@456. Null uses github_repository."
  type        = string
  default     = null

  validation {
    condition     = var.github_subject_repository == null || can(regex("^[A-Za-z0-9_.-]+@[0-9]+/[A-Za-z0-9_.-]+@[0-9]+$", var.github_subject_repository))
    error_message = "github_subject_repository must look like owner@<owner-id>/name@<repo-id>."
  }
}

variable "create_provider" {
  description = "Create the GitHub OIDC identity provider. It is account-wide, so only one environment per account should create it."
  type        = bool
  default     = true
}

variable "existing_provider_arn" {
  description = "ARN of an existing GitHub OIDC provider, used when create_provider is false."
  type        = string
  default     = null

  validation {
    condition     = var.existing_provider_arn == null || can(regex("^arn:aws:iam::[0-9]{12}:oidc-provider/token.actions.githubusercontent.com$", var.existing_provider_arn))
    error_message = "existing_provider_arn must be the ARN of the token.actions.githubusercontent.com provider."
  }
}

variable "apply_environments" {
  description = "GitHub environments whose jobs may assume the apply role. Protect them with required reviewers and a main-only branch rule."
  type        = list(string)

  validation {
    condition     = length(var.apply_environments) > 0
    error_message = "At least one protected GitHub environment is required for the apply role."
  }
}

variable "state_bucket_arn" {
  description = "ARN of the Terraform state bucket."
  type        = string
}

variable "plan_role_name" {
  description = "Name of the read-only plan role."
  type        = string
}

variable "apply_role_name" {
  description = "Name of the apply role."
  type        = string
}

variable "apply_max_session_seconds" {
  description = "Maximum session length for the apply role (a dev apply-verify-destroy cycle runs ~45 minutes)."
  type        = number
  default     = 3600

  validation {
    condition     = var.apply_max_session_seconds >= 3600 && var.apply_max_session_seconds <= 14400
    error_message = "apply_max_session_seconds must be between 1 and 4 hours."
  }
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
