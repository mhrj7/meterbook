output "plan_role_arn" {
  description = "Role for terraform plan in GitHub Actions."
  value       = aws_iam_role.plan.arn
}

output "apply_role_arn" {
  description = "Role for terraform apply in protected GitHub environments."
  value       = aws_iam_role.apply.arn
}

output "provider_arn" {
  description = "GitHub OIDC provider ARN."
  value       = local.provider_arn
}

output "plan_trust_policy" {
  description = "Trust policy of the plan role (exposed for tests and audits)."
  value       = local.plan_trust_policy
}

output "apply_trust_policy" {
  description = "Trust policy of the apply role (exposed for tests and audits)."
  value       = local.apply_trust_policy
}

output "plan_state_policy" {
  description = "State-bucket policy of the plan role (exposed for tests and audits)."
  value       = local.plan_state_policy
}
