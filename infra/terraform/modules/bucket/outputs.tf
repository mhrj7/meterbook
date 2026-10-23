output "id" {
  description = "Bucket name."
  value       = aws_s3_bucket.this.id
}

output "arn" {
  description = "Bucket ARN."
  value       = aws_s3_bucket.this.arn
}

output "policy" {
  description = "Bucket policy JSON (exposed for tests and audits)."
  value       = local.policy
}
