# Unit tests for the bucket security baseline. Runs offline against a mocked AWS provider:
#   terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_resource "aws_s3_bucket" {
    defaults = {
      arn = "arn:aws:s3:::meterbook-test-bucket"
    }
  }
}

variables {
  name = "meterbook-test-bucket"
}

run "blocks_all_public_access" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "All four public access block settings must be true."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.this.rule[0].object_ownership == "BucketOwnerEnforced"
    error_message = "ACLs must be disabled (BucketOwnerEnforced)."
  }
}

run "versioned_and_encrypted_with_sse_s3_by_default" {
  command = plan

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "Versioning must be enabled."
  }

  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Default encryption must be SSE-S3 when no KMS key is given."
  }

  assert {
    condition     = aws_s3_bucket.this.force_destroy == false
    error_message = "force_destroy must default to false."
  }
}

run "uses_sse_kms_when_key_given" {
  command = plan

  variables {
    kms_key_arn = "arn:aws:kms:us-east-1:111122223333:key/11111111-2222-3333-4444-555555555555"
  }

  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default).sse_algorithm == "aws:kms"
    error_message = "SSE-KMS must be used when a KMS key is given."
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.this.rule).bucket_key_enabled == true
    error_message = "S3 bucket keys must be enabled with SSE-KMS to cut KMS costs."
  }
}

run "policy_denies_plaintext_and_old_tls" {
  command = apply

  assert {
    condition = anytrue([
      for s in jsondecode(output.policy).Statement :
      s.Effect == "Deny" && try(s.Condition.Bool["aws:SecureTransport"], "") == "false"
    ])
    error_message = "Policy must deny requests without TLS."
  }

  assert {
    condition = anytrue([
      for s in jsondecode(output.policy).Statement :
      s.Effect == "Deny" && try(s.Condition.NumericLessThan["s3:TlsVersion"], "") == "1.2"
    ])
    error_message = "Policy must deny TLS versions below 1.2."
  }

  assert {
    condition = alltrue([
      for s in jsondecode(output.policy).Statement :
      contains(s.Resource, "arn:aws:s3:::meterbook-test-bucket/*")
    ])
    error_message = "Every deny statement must cover the objects as well as the bucket."
  }
}

run "rejects_invalid_bucket_name" {
  command = plan

  variables {
    name = "Invalid_Bucket"
  }

  expect_failures = [var.name]
}

run "rejects_zero_day_version_retention" {
  command = plan

  variables {
    noncurrent_version_retention_days = 0
  }

  expect_failures = [var.noncurrent_version_retention_days]
}
