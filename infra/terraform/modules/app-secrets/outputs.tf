output "secret_names" {
  description = "Full Secrets Manager names by short name (remote keys for ExternalSecrets)."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.name }
}

output "secret_arns" {
  description = "Secret ARNs by short name."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.arn }
}

output "kms_key_arn" {
  description = "KMS key encrypting the application secrets."
  value       = aws_kms_key.this.arn
}

output "eso_role_arn" {
  description = "Role External Secrets Operator assumes through Pod Identity."
  value       = aws_iam_role.eso.arn
}

output "eso_trust_policy" {
  description = "Trust policy of the ESO role (exposed for tests and audits)."
  value       = local.eso_trust_policy
}

output "eso_read_policy" {
  description = "Read policy of the ESO role (exposed for tests and audits)."
  value       = local.eso_read_policy
}
