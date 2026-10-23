# Unit tests for application secrets and ESO access (mocked AWS provider, runs offline).

mock_provider "aws" {
  mock_data "aws_region" {
    defaults = {
      region = "us-east-1"
    }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:us-east-1:111122223333:key/11111111-2222-3333-4444-555555555555"
      key_id = "11111111-2222-3333-4444-555555555555"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111122223333:role/meterbook-test-external-secrets"
    }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = {
      arn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:meterbook-test/mock-AbCdEf"
    }
  }
}

variables {
  name         = "meterbook-test"
  cluster_name = "meterbook-test"
  secret_names = ["stripe-api-key", "stripe-webhook-signing-secret"]
  extra_readable_secret_arns = [
    "arn:aws:secretsmanager:us-east-1:111122223333:secret:rds!db-1234-AbCdEf",
  ]
}

run "secrets_are_prefixed_encrypted_and_recoverable" {
  command = plan

  assert {
    condition = (
      aws_secretsmanager_secret.this["stripe-api-key"].name == "meterbook-test/stripe-api-key" &&
      aws_secretsmanager_secret.this["stripe-webhook-signing-secret"].name == "meterbook-test/stripe-webhook-signing-secret"
    )
    error_message = "Secrets must be named <name>/<secret>."
  }

  assert {
    condition     = alltrue([for s in aws_secretsmanager_secret.this : s.recovery_window_in_days == 30])
    error_message = "Deleted secrets must be recoverable for 30 days by default."
  }

  assert {
    condition     = aws_kms_key.this.enable_key_rotation == true
    error_message = "The secrets KMS key must rotate."
  }
}

run "eso_reads_exactly_the_listed_secrets" {
  command = apply

  assert {
    condition = alltrue([
      for s in jsondecode(output.eso_read_policy).Statement :
      alltrue([for r in s.Resource : !strcontains(r, "*")])
    ])
    error_message = "ESO policy must not contain wildcard resources."
  }

  assert {
    condition = length(one([
      for s in jsondecode(output.eso_read_policy).Statement : s.Resource if s.Sid == "ReadListedSecretsOnly"
    ])) == 3
    error_message = "ESO may read the two app secrets plus the one extra (RDS) secret, nothing else."
  }

  assert {
    condition = contains(one([
      for s in jsondecode(output.eso_read_policy).Statement : s.Resource if s.Sid == "ReadListedSecretsOnly"
    ]), "arn:aws:secretsmanager:us-east-1:111122223333:secret:rds!db-1234-AbCdEf")
    error_message = "The extra RDS secret must be readable."
  }

  assert {
    condition = one([
      for s in jsondecode(output.eso_read_policy).Statement :
      s.Condition.StringEquals["kms:ViaService"] if s.Sid == "DecryptViaSecretsManagerOnly"
    ]) == "secretsmanager.us-east-1.amazonaws.com"
    error_message = "KMS decrypt must be allowed only via Secrets Manager in this region."
  }

  assert {
    condition = alltrue([
      for s in jsondecode(output.eso_read_policy).Statement :
      alltrue([for a in s.Action : !contains(["secretsmanager:PutSecretValue", "secretsmanager:DeleteSecret", "secretsmanager:*", "kms:*"], a)])
    ])
    error_message = "ESO must be read-only."
  }
}

run "role_is_bound_to_eso_service_account_via_pod_identity" {
  command = plan

  assert {
    condition     = jsondecode(aws_iam_role.eso.assume_role_policy).Statement[0].Principal.Service == "pods.eks.amazonaws.com"
    error_message = "Only EKS Pod Identity may assume the ESO role."
  }

  assert {
    condition = (
      aws_eks_pod_identity_association.eso.namespace == "external-secrets" &&
      aws_eks_pod_identity_association.eso.service_account == "external-secrets" &&
      aws_eks_pod_identity_association.eso.cluster_name == "meterbook-test"
    )
    error_message = "The role must be associated with the ESO service account only."
  }
}

run "rejects_force_delete_recovery_window" {
  command = plan

  variables {
    recovery_window_days = 0
  }

  expect_failures = [var.recovery_window_days]
}

run "rejects_invalid_secret_name" {
  command = plan

  variables {
    secret_names = ["Stripe_API_Key"]
  }

  expect_failures = [var.secret_names]
}

run "rejects_duplicate_secret_names" {
  command = plan

  variables {
    secret_names = ["stripe-api-key", "stripe-api-key"]
  }

  expect_failures = [var.secret_names]
}

run "rejects_wildcard_extra_arn" {
  command = plan

  variables {
    extra_readable_secret_arns = ["arn:aws:secretsmanager:us-east-1:111122223333:secret:*"]
  }

  expect_failures = [var.extra_readable_secret_arns]
}
