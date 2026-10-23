variable "name" {
  description = "Cluster name."
  type        = string
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version (major.minor)."
  type        = string
  default     = "1.35"

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.kubernetes_version))
    error_message = "kubernetes_version must look like 1.35."
  }
}

variable "vpc_id" {
  description = "VPC for the cluster."
  type        = string
}

variable "node_subnet_ids" {
  description = "Private subnets for nodes and pods."
  type        = list(string)
}

variable "control_plane_subnet_ids" {
  description = "Intra subnets for control-plane ENIs."
  type        = list(string)
}

variable "endpoint_public_access" {
  description = "Expose the Kubernetes API publicly (restricted to endpoint_public_access_cidrs)."
  type        = bool
  default     = false
}

variable "endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the public API endpoint. Never 0.0.0.0/0."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.endpoint_public_access_cidrs, "0.0.0.0/0")
    error_message = "The Kubernetes API must not be open to the whole internet."
  }
}

variable "node_instance_types" {
  description = "Instance types for the default node group (arm64 / Graviton)."
  type        = list(string)
  default     = ["t4g.medium"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT."
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type must be ON_DEMAND or SPOT."
  }
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
  description = "Desired nodes at creation."
  type        = number
  default     = 2

  validation {
    condition     = var.node_desired_size >= var.node_min_size && var.node_desired_size <= var.node_max_size
    error_message = "node_desired_size must be between node_min_size and node_max_size."
  }
}

variable "log_retention_days" {
  description = "CloudWatch retention for control-plane logs."
  type        = number
  default     = 30
}

variable "deletion_protection" {
  description = "Block deletion of the cluster."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
