# Unit tests for the GitOps bootstrap (mocked Helm provider, runs offline).

mock_provider "helm" {}

variables {
  environments = ["dev", "staging"]
}

run "installs_roots_for_each_environment_on_the_cluster" {
  command = plan

  assert {
    condition     = jsonencode(output.root_applications) == jsonencode(["meterbook-dev-root", "meterbook-staging-root"])
    error_message = "One root Application per environment on this cluster."
  }

  assert {
    condition = alltrue([
      for app in values(output.roots_values.applications) :
      app.project == "bootstrap" && app.namespace == "argocd" && alltrue([for s in app.sources : s.targetRevision == "main"])
    ])
    error_message = "Roots must use the bootstrap project, live in argocd, and track main."
  }

  assert {
    condition = anytrue([
      for s in output.roots_values.applications["meterbook-dev-root"].sources : s.path == "infra/argocd/envs/dev"
    ])
    error_message = "The dev root must read infra/argocd/envs/dev."
  }

  assert {
    condition     = jsonencode(keys(output.roots_values.projects)) == jsonencode(["bootstrap"])
    error_message = "Only the bootstrap project is created by Terraform; the rest is GitOps-managed."
  }
}

run "bootstrap_project_can_only_create_argocd_objects" {
  command = plan

  assert {
    condition = (
      length(output.roots_values.projects["bootstrap"].clusterResourceWhitelist) == 0 &&
      alltrue([for k in output.roots_values.projects["bootstrap"].namespaceResourceWhitelist : k.group == "argoproj.io"]) &&
      alltrue([for d in output.roots_values.projects["bootstrap"].destinations : d.namespace == "argocd"])
    )
    error_message = "The bootstrap project must be limited to ArgoCD objects in the argocd namespace."
  }
}

run "argocd_chart_pinned_and_hardened" {
  command = plan

  assert {
    condition     = helm_release.argocd.version == "10.11.0" && helm_release.roots.version == "2.0.7"
    error_message = "Chart versions must be pinned."
  }

  assert {
    condition     = yamldecode(helm_release.argocd.values[0]).server.service.type == "ClusterIP"
    error_message = "The ArgoCD server must not be exposed through a load balancer."
  }

  assert {
    condition     = yamldecode(helm_release.argocd.values[0]).dex.enabled == false
    error_message = "Unused components (dex) must be disabled."
  }
}

run "prod_cluster_runs_only_prod" {
  command = plan

  variables {
    environments = ["prod"]
  }

  assert {
    condition     = jsonencode(output.root_applications) == jsonencode(["meterbook-prod-root"])
    error_message = "The prod cluster runs only the prod root."
  }
}

run "rejects_prod_sharing_a_cluster" {
  command = plan

  variables {
    environments = ["staging", "prod"]
  }

  expect_failures = [var.environments]
}

run "rejects_unknown_environment" {
  command = plan

  variables {
    environments = ["qa"]
  }

  expect_failures = [var.environments]
}
