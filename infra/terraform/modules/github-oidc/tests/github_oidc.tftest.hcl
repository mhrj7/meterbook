# Unit tests for CI access to AWS: who can assume which role (mocked AWS provider, runs offline).

mock_provider "aws" {
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
    }
  }
}

variables {
  github_repository  = "acme/meterbook"
  apply_environments = ["dev"]
  state_bucket_arn   = "arn:aws:s3:::meterbook-tfstate-111122223333"
  plan_role_name     = "meterbook-dev-terraform-plan"
  apply_role_name    = "meterbook-dev-terraform-apply"
}

run "plan_role_trusts_only_this_repo_prs_and_main" {
  command = apply

  assert {
    condition = (
      jsondecode(output.plan_trust_policy).Statement[0].Condition.StringLike["token.actions.githubusercontent.com:sub"]
      == ["repo:acme/meterbook:pull_request", "repo:acme/meterbook:ref:refs/heads/main"]
    )
    error_message = "Plan role must trust only pull requests and main of this repository."
  }

  assert {
    condition     = jsondecode(output.plan_trust_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Audience must be pinned to sts.amazonaws.com."
  }

  assert {
    condition     = aws_iam_role_policy_attachment.plan_read_only.policy_arn == "arn:aws:iam::aws:policy/ReadOnlyAccess"
    error_message = "Plan role must be read-only."
  }
}

run "apply_role_trusts_only_protected_environments" {
  command = apply

  assert {
    condition = (
      jsondecode(output.apply_trust_policy).Statement[0].Condition.StringLike["token.actions.githubusercontent.com:sub"]
      == ["repo:acme/meterbook:environment:dev"]
    )
    error_message = "Apply role must trust only the listed GitHub environments."
  }

  assert {
    condition = alltrue([
      for s in jsondecode(output.apply_trust_policy).Statement[0].Condition.StringLike["token.actions.githubusercontent.com:sub"] :
      !strcontains(s, "pull_request") && !strcontains(s, "*")
    ])
    error_message = "Apply role must never be assumable from pull requests or wildcard subjects."
  }
}

run "plan_role_can_write_only_lock_files" {
  command = apply

  assert {
    condition = alltrue([
      for s in jsondecode(output.plan_state_policy).Statement :
      alltrue([for r in s.Resource : endswith(r, ".tflock")])
      if contains(s.Action, "s3:PutObject") || contains(s.Action, "s3:DeleteObject")
    ])
    error_message = "Plan role may only write or delete *.tflock objects."
  }
}

run "reuses_existing_provider_when_not_creating" {
  command = plan

  variables {
    create_provider       = false
    existing_provider_arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0
    error_message = "No provider must be created when reusing an existing one."
  }

  assert {
    condition     = output.provider_arn == "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
    error_message = "The existing provider ARN must be used."
  }
}

run "rejects_wildcard_repository" {
  command = plan

  variables {
    github_repository = "acme/*"
  }

  expect_failures = [var.github_repository]
}

run "rejects_empty_apply_environments" {
  command = plan

  variables {
    apply_environments = []
  }

  expect_failures = [var.apply_environments]
}
