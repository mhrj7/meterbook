# Unit tests for the subnet layout (mocked AWS provider, runs offline).

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c", "us-east-1d"]
    }
  }

  # Upstream modules render IAM policies through this data source; the mock needs valid JSON.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  name = "meterbook-test"
}

run "three_azs_with_non_overlapping_tiers" {
  command = plan

  assert {
    condition     = output.azs == tolist(["us-east-1a", "us-east-1b", "us-east-1c"])
    error_message = "Must use the first three available AZs."
  }

  assert {
    condition     = jsonencode(output.subnet_cidrs.private) == jsonencode(["10.0.0.0/20", "10.0.16.0/20", "10.0.32.0/20"])
    error_message = "Private subnets must be /20s for pod IP capacity."
  }

  assert {
    condition = length(distinct(concat(
      output.subnet_cidrs.private, output.subnet_cidrs.public,
      output.subnet_cidrs.database, output.subnet_cidrs.intra,
    ))) == 12
    error_message = "All 12 subnets must be distinct."
  }

  assert {
    condition = alltrue([
      for c in concat(output.subnet_cidrs.public, output.subnet_cidrs.database, output.subnet_cidrs.intra) :
      tonumber(split(".", c)[2]) >= 48
    ])
    error_message = "/24 tiers must start above the /20 private range (10.0.48.0 and up)."
  }
}

run "two_azs" {
  command = plan

  variables {
    az_count = 2
  }

  assert {
    condition     = length(output.subnet_cidrs.private) == 2 && length(output.subnet_cidrs.database) == 2
    error_message = "Every tier must have one subnet per AZ."
  }
}

run "rejects_single_az" {
  command = plan

  variables {
    az_count = 1
  }

  expect_failures = [var.az_count]
}

run "rejects_non_16_cidr" {
  command = plan

  variables {
    cidr = "10.0.0.0/20"
  }

  expect_failures = [var.cidr]
}
