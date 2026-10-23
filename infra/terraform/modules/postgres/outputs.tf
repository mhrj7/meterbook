output "address" {
  description = "Hostname of the instance."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Port of the instance."
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "Initial database name."
  value       = aws_db_instance.this.db_name
}

output "master_user_secret_arn" {
  description = "Secrets Manager secret holding the RDS-managed master credentials."
  value       = try(aws_db_instance.this.master_user_secret[0].secret_arn, null)
}

output "security_group_id" {
  description = "Security group attached to the instance."
  value       = aws_security_group.this.id
}
