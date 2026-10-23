output "state_bucket" {
  description = "Name of the Terraform state bucket."
  value       = module.state_bucket.id
}

output "backend_config" {
  description = "Paste into envs/<env>/backend.hcl."
  value       = <<-EOT
    bucket       = "${module.state_bucket.id}"
    region       = "${var.region}"
    encrypt      = true
    use_lockfile = true
  EOT
}
