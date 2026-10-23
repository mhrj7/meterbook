# Environment guard rails: what separates a disposable dev stack from production (mocked providers, offline).

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
      arn        = "arn:aws:iam::111122223333:role/meterbook-terraform-apply"
    }
  }
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }
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
  mock_data "aws_iam_session_context" {
    defaults = {
      issuer_arn = "arn:aws:iam::111122223333:role/meterbook-terraform-apply"
    }
  }
  mock_data "aws_eks_addon_version" {
    defaults = {
      version = "v1.0.0-eksbuild.1"
    }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
    }
  }
}

mock_provider "tls" {}
mock_provider "time" {}
mock_provider "cloudinit" {}
mock_provider "null" {}

variables {
  environment              = "dev"
  github_repository        = "acme/meterbook"
  github_apply_environment = "dev"
  state_bucket_name        = "meterbook-tfstate-111122223333"
}

run "dev_stack_plans_with_account_scoped_bucket_names" {
  command = plan

  assert {
    condition = (
      output.buckets["invoices"] == "meterbook-dev-invoices-111122223333" &&
      output.buckets["exports"] == "meterbook-dev-exports-111122223333"
    )
    error_message = "Bucket names must be <product>-<env>-<purpose>-<account>."
  }
}

run "prod_rejects_single_az_database" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_backup_retention_days = 14
    db_multi_az              = false
  }

  expect_failures = [var.db_multi_az]
}

run "prod_rejects_short_backup_retention" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_multi_az              = true
    db_backup_retention_days = 7
  }

  expect_failures = [var.db_backup_retention_days]
}

run "prod_rejects_shared_nat_gateway" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_multi_az              = true
    db_backup_retention_days = 14
    single_nat_gateway       = true
  }

  expect_failures = [var.single_nat_gateway]
}

run "prod_can_never_be_disposable" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_multi_az              = true
    db_backup_retention_days = 14
    disposable               = true
  }

  expect_failures = [var.disposable]
}

run "prod_rejects_two_azs" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_multi_az              = true
    db_backup_retention_days = 14
    az_count                 = 2
  }

  expect_failures = [var.az_count]
}

run "valid_prod_stack_plans" {
  command = plan

  variables {
    environment              = "prod"
    github_apply_environment = "production"
    db_multi_az              = true
    db_backup_retention_days = 14
  }

  assert {
    condition     = output.buckets["invoices"] == "meterbook-prod-invoices-111122223333"
    error_message = "A compliant prod configuration must plan."
  }
}
