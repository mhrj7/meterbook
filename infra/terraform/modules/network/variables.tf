variable "name" {
  description = "Name prefix for the VPC and its resources."
  type        = string
}

variable "cidr" {
  description = "VPC CIDR block. Must be a /16 so the subnet layout fits."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.cidr, 0)) && endswith(var.cidr, "/16")
    error_message = "cidr must be a valid /16 block."
  }
}

variable "az_count" {
  description = "Number of availability zones (2 or 3)."
  type        = number
  default     = 3

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be 2 or 3 (EKS and multi-AZ RDS need at least 2)."
  }
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (cheap, single point of failure) instead of one per AZ."
  type        = bool
  default     = false
}

variable "flow_log_retention_days" {
  description = "CloudWatch retention for VPC flow logs."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
