# Unit tests for cluster guard rails (mocked AWS provider, runs offline).

mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
      arn        = "arn:aws:iam::111122223333:role/meterbook-dev-terraform-apply"
    }
  }
  mock_data "aws_iam_session_context" {
    defaults = {
      issuer_arn = "arn:aws:iam::111122223333:role/meterbook-dev-terraform-apply"
    }
  }
  mock_data "aws_eks_addon_version" {
    defaults = {
      version = "v1.0.0-eksbuild.1"
    }
  }
}

mock_provider "tls" {}
mock_provider "time" {}
mock_provider "cloudinit" {}
mock_provider "null" {}

variables {
  name                     = "meterbook-test"
  vpc_id                   = "vpc-0123456789abcdef0"
  node_subnet_ids          = ["subnet-0aaaaaaaaaaaaaaa1", "subnet-0aaaaaaaaaaaaaaa2"]
  control_plane_subnet_ids = ["subnet-0bbbbbbbbbbbbbbb1", "subnet-0bbbbbbbbbbbbbbb2"]
}

run "private_api_by_default" {
  command = plan

  assert {
    condition     = output.api_access.public_endpoint == false && length(output.api_access.public_cidrs) == 0
    error_message = "The Kubernetes API must be private by default."
  }
}

run "public_api_only_from_allowed_cidrs" {
  command = plan

  variables {
    endpoint_public_access       = true
    endpoint_public_access_cidrs = ["203.0.113.10/32"]
  }

  assert {
    condition     = output.api_access.public_endpoint && output.api_access.public_cidrs == tolist(["203.0.113.10/32"])
    error_message = "Public API access must be limited to the allowed CIDRs."
  }
}

run "cidrs_ignored_when_public_endpoint_off" {
  command = plan

  variables {
    endpoint_public_access_cidrs = ["203.0.113.10/32"]
  }

  assert {
    condition     = length(output.api_access.public_cidrs) == 0
    error_message = "CIDRs must not be applied while the public endpoint is off."
  }
}

run "rejects_api_open_to_internet" {
  command = plan

  variables {
    endpoint_public_access       = true
    endpoint_public_access_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.endpoint_public_access_cidrs]
}

run "rejects_unknown_capacity_type" {
  command = plan

  variables {
    node_capacity_type = "RESERVED"
  }

  expect_failures = [var.node_capacity_type]
}

run "rejects_desired_outside_bounds" {
  command = plan

  variables {
    node_min_size     = 2
    node_max_size     = 3
    node_desired_size = 5
  }

  expect_failures = [var.node_desired_size]
}

run "rejects_unknown_ami_type" {
  command = plan

  variables {
    node_ami_type = "BOTTLEROCKET_ARM_64"
  }

  expect_failures = [var.node_ami_type]
}

run "rejects_malformed_version" {
  command = plan

  variables {
    kubernetes_version = "1.35.2"
  }

  expect_failures = [var.kubernetes_version]
}
