output "state_bucket" {
  description = "Name of the Terraform state bucket (repository variable TF_STATE_BUCKET)."
  value       = module.state_bucket.id
}

output "ci_roles" {
  description = "Set as repository variables TF_PLAN_ROLE and TF_APPLY_ROLE."
  value = {
    plan  = module.github_oidc.plan_role_arn
    apply = module.github_oidc.apply_role_arn
  }
}
