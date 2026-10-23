variable "name" {
  description = "Instance identifier, e.g. meterbook-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC the database security group belongs to."
  type        = string
}

variable "db_subnet_group_name" {
  description = "DB subnet group spanning private database subnets."
  type        = string
}

variable "allowed_security_group_ids" {
  description = "Security groups allowed to connect on 5432 (e.g. the EKS node group)."
  type        = list(string)
  default     = []
}

variable "engine_version" {
  description = "PostgreSQL version. A major version (\"17\") lets RDS pick the latest minor."
  type        = string
  default     = "17"
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "database_name" {
  description = "Initial database name."
  type        = string
  default     = "meterbook"
}

variable "master_username" {
  description = "Master user name. The password is managed by RDS in Secrets Manager."
  type        = string
  default     = "meterbook_admin"
}

variable "allocated_storage_gb" {
  description = "Initial storage in GiB."
  type        = number
  default     = 20
}

variable "max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling in GiB."
  type        = number
  default     = 100

  validation {
    condition     = var.max_allocated_storage_gb >= var.allocated_storage_gb
    error_message = "max_allocated_storage_gb must be at least allocated_storage_gb."
  }
}

variable "kms_key_arn" {
  description = "KMS key for storage encryption. Null uses the AWS-managed RDS key."
  type        = string
  default     = null
}

variable "multi_az" {
  description = "Run a synchronous standby in a second AZ. Required for prod."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Automated backup retention (point-in-time recovery window)."
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "Backups must be kept between 1 and 35 days; 0 would disable point-in-time recovery."
  }
}

variable "deletion_protection" {
  description = "Block deletion of the instance."
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot on deletion. Only for disposable environments."
  type        = bool
  default     = false
}

variable "performance_insights_enabled" {
  description = "Enable Performance Insights."
  type        = bool
  default     = false
}

variable "log_min_duration_ms" {
  description = "Log statements slower than this many milliseconds."
  type        = number
  default     = 500
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
