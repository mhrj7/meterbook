# Unit tests for the PostgreSQL security and durability baseline (mocked AWS provider, runs offline).

mock_provider "aws" {}

variables {
  name                       = "meterbook-test"
  vpc_id                     = "vpc-0123456789abcdef0"
  db_subnet_group_name       = "meterbook-test-db"
  allowed_security_group_ids = ["sg-0aaaaaaaaaaaaaaaa"]
}

run "private_encrypted_and_password_managed_by_rds" {
  command = plan

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false
    error_message = "The database must never be publicly accessible."
  }

  assert {
    condition     = aws_db_instance.this.storage_encrypted == true
    error_message = "Storage must be encrypted."
  }

  assert {
    condition     = aws_db_instance.this.manage_master_user_password == true && aws_db_instance.this.password == null
    error_message = "The master password must be managed by RDS, never passed through Terraform."
  }

  assert {
    condition     = aws_db_instance.this.deletion_protection == true && aws_db_instance.this.skip_final_snapshot == false
    error_message = "Deletion protection and a final snapshot must be on by default."
  }

  assert {
    condition     = aws_db_instance.this.backup_retention_period >= 1
    error_message = "Point-in-time recovery must be enabled."
  }
}

run "tls_is_enforced_and_family_matches_version" {
  command = plan

  assert {
    condition = anytrue([
      for p in aws_db_parameter_group.this.parameter : p.name == "rds.force_ssl" && p.value == "1"
    ])
    error_message = "rds.force_ssl must be 1."
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres17"
    error_message = "Parameter group family must follow the engine major version."
  }
}

run "family_follows_minor_version_input" {
  command = plan

  variables {
    engine_version = "16.4"
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres16"
    error_message = "A minor version input must map to its major family."
  }
}

run "only_approved_security_groups_reach_5432" {
  command = plan

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.postgres) == 1
    error_message = "Exactly one ingress rule per approved security group."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.postgres[0].referenced_security_group_id == "sg-0aaaaaaaaaaaaaaaa" &&
      aws_vpc_security_group_ingress_rule.postgres[0].from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.postgres[0].to_port == 5432 &&
      aws_vpc_security_group_ingress_rule.postgres[0].cidr_ipv4 == null
    )
    error_message = "Ingress must be 5432 from the approved security group only, never from a CIDR."
  }
}

run "disposable_environment_skips_final_snapshot" {
  command = plan

  variables {
    deletion_protection = false
    skip_final_snapshot = true
  }

  assert {
    condition     = aws_db_instance.this.final_snapshot_identifier == null
    error_message = "No final snapshot identifier when skipping the final snapshot."
  }
}

run "rejects_disabled_backups" {
  command = plan

  variables {
    backup_retention_days = 0
  }

  expect_failures = [var.backup_retention_days]
}

run "rejects_storage_ceiling_below_initial_size" {
  command = plan

  variables {
    allocated_storage_gb     = 50
    max_allocated_storage_gb = 20
  }

  expect_failures = [var.max_allocated_storage_gb]
}
