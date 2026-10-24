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
  mock_data "aws_region" {
    defaults = {
      region = "us-east-1"
    }
  }
}

mock_provider "tls" {}
mock_provider "time" {}
mock_provider "cloudinit" {}
mock_provider "null" {}

variables {
  environment    = "dev"
  app_namespaces = ["meterbook-dev", "meterbook-staging"]
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

  assert {
    condition     = output.app_secrets.names["stripe-api-key"] == "meterbook-dev/stripe-api-key"
    error_message = "App secrets must be namespaced by environment."
  }
}

run "prod_rejects_single_az_database" {
  command = plan

  variables {
    environment              = "prod"
    db_backup_retention_days = 14
    db_multi_az              = false
  }

  expect_failures = [var.db_multi_az]
}

run "prod_rejects_short_backup_retention" {
  command = plan

  variables {
    environment              = "prod"
    db_multi_az              = true
    db_backup_retention_days = 7
  }

  expect_failures = [var.db_backup_retention_days]
}

run "prod_rejects_shared_nat_gateway" {
  command = plan

  variables {
    environment              = "prod"
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
    db_multi_az              = true
    db_backup_retention_days = 14
  }

  assert {
    condition     = output.buckets["invoices"] == "meterbook-prod-invoices-111122223333"
    error_message = "A compliant prod configuration must plan."
  }
}

run "db_bootstrap_reads_only_the_master_credentials" {
  command = plan

  override_module {
    target = module.postgres
    outputs = {
      address                = "meterbook-dev.example.eu-north-1.rds.amazonaws.com"
      port                   = 5432
      database_name          = "meterbook"
      master_user_secret_arn = "arn:aws:secretsmanager:eu-north-1:111122223333:secret:rds!db-0000-AbCdEf"
      security_group_id      = "sg-0000"
    }
  }

  assert {
    condition     = jsonencode(jsondecode(aws_iam_role_policy.db_bootstrap.policy).Statement) == jsonencode([{ Sid = "ReadMasterCredentialsOnly", Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = ["arn:aws:secretsmanager:eu-north-1:111122223333:secret:rds!db-0000-AbCdEf"] }])
    error_message = "db-bootstrap may read the RDS master secret and nothing else."
  }

  assert {
    condition     = jsonencode(sort(keys(aws_eks_pod_identity_association.db_bootstrap))) == jsonencode(["meterbook-dev", "meterbook-staging"]) && alltrue([for a in aws_eks_pod_identity_association.db_bootstrap : a.service_account == "db-bootstrap"])
    error_message = "Each application namespace gets a db-bootstrap binding."
  }

  assert {
    condition     = module.app_secrets.secret_names["database"] == "meterbook-dev/database"
    error_message = "The database location is published as meterbook-<env>/database."
  }

  assert {
    condition     = jsonencode(sort(keys(jsondecode(aws_secretsmanager_secret_version.database.secret_string)))) == jsonencode(["host", "master_secret_arn", "port"])
    error_message = "The database secret holds location metadata only, never credentials."
  }
}

run "rejects_namespaces_of_other_products" {
  command = plan

  variables {
    app_namespaces = ["kube-system"]
  }

  expect_failures = [var.app_namespaces]
}
