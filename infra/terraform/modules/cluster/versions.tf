terraform {
  required_version = ">= 1.10"

  required_providers {
    # Used only by the wrapped EKS module, but declared here to pin its version and so `terraform test` can mock it.
    # tflint-ignore: terraform_unused_required_providers
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
