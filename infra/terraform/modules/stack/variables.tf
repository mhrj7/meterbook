variable "environment" {
  description = "Environment name."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be dev or prod."
  }
}

variable "disposable" {
  description = "Environment can be destroyed without ceremony: no deletion protection, no final DB snapshot, buckets force-destroyed. Never for prod."
  type        = bool
  default     = false

  validation {
    condition     = !(var.disposable && var.environment == "prod")
    error_message = "prod can never be disposable."
  }
}

# --- network ---------------------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC /16."
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "Availability zones."
  type        = number
  default     = 3

  validation {
    condition     = var.environment != "prod" || var.az_count == 3
    error_message = "prod must span 3 availability zones."
  }
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway across AZs."
  type        = bool
  default     = false

  validation {
    condition     = var.environment != "prod" || !var.single_nat_gateway
    error_message = "prod needs one NAT gateway per AZ so an AZ failure does not cut egress."
  }
}

variable "log_retention_days" {
  description = "Retention for flow logs and control-plane logs."
  type        = number
  default     = 30
}

# --- cluster ---------------------------------------------------------------------------------------------------

variable "kubernetes_version" {
  description = "EKS version."
  type        = string
  default     = "1.35"
}

variable "endpoint_public_access" {
  description = "Expose the Kubernetes API publicly (restricted by CIDR)."
  type        = bool
  default     = false
}

variable "endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach a public Kubernetes API."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Node instance types (arm64)."
  type        = list(string)
  default     = ["t4g.medium"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT."
  type        = string
  default     = "ON_DEMAND"
}

variable "node_min_size" {
  description = "Minimum nodes."
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "Maximum nodes."
  type        = number
  default     = 4
}

variable "node_desired_size" {
  description = "Desired nodes."
  type        = number
  default     = 2
}

# --- database --------------------------------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_multi_az" {
  description = "Synchronous standby in a second AZ."
  type        = bool
  default     = false

  validation {
    condition     = var.environment != "prod" || var.db_multi_az
    error_message = "prod databases must be multi-AZ."
  }
}

variable "db_backup_retention_days" {
  description = "Point-in-time recovery window."
  type        = number
  default     = 7

  validation {
    condition     = var.environment != "prod" || var.db_backup_retention_days >= 14
    error_message = "prod must keep at least 14 days of backups."
  }
}

variable "db_performance_insights" {
  description = "Enable Performance Insights."
  type        = bool
  default     = false
}

# --- application secrets ---------------------------------------------------------------------------------------

variable "app_secret_names" {
  description = "Application secrets created as empty shells (values set out-of-band, ADR-0004)."
  type        = list(string)
  default = [
    "stripe-api-key",
    "stripe-webhook-signing-secret",
    "identity-oidc-client-secret",
  ]
}

# --- CI access -------------------------------------------------------------------------------------------------

variable "github_repository" {
  description = "owner/name of the GitHub repository that runs Terraform."
  type        = string
}

variable "github_apply_environment" {
  description = "Protected GitHub environment whose jobs may apply this environment."
  type        = string
}

variable "github_create_oidc_provider" {
  description = "Create the account-wide GitHub OIDC provider (only one environment per AWS account)."
  type        = bool
  default     = true
}

variable "github_existing_oidc_provider_arn" {
  description = "Existing GitHub OIDC provider when not creating one."
  type        = string
  default     = null
}

variable "state_bucket_name" {
  description = "Terraform state bucket created by bootstrap/."
  type        = string
}

variable "tags" {
  description = "Extra tags for every resource."
  type        = map(string)
  default     = {}
}
