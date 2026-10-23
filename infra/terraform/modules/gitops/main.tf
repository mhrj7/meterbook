# Installs ArgoCD and the GitOps roots on one cluster (ADR-0006). The roots, the bootstrap project and everything they
# deploy are YAML in infra/argocd; this module only loads them, so Git stays the single source of truth.

locals {
  argocd_dir = "${path.module}/../../../argocd"
  project    = yamldecode(file("${local.argocd_dir}/bootstrap/project.yaml"))
  roots      = [for env in var.environments : yamldecode(file("${local.argocd_dir}/roots/${env}.yaml"))]

  # argocd-apps chart format: a map keyed by name, holding the namespace plus the object's spec.
  roots_values = {
    projects = {
      (local.project.metadata.name) = merge({ namespace = local.project.metadata.namespace }, local.project.spec)
    }
    applications = {
      for r in local.roots : r.metadata.name => merge({ namespace = r.metadata.namespace }, r.spec)
    }
  }

  argocd_values_file = "${local.argocd_dir}/install/argocd-values.yaml"
  argocd_overrides = {
    configs = { cm = { "timeout.reconciliation" = var.reconciliation_timeout } }
  }
}

resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = var.argocd_chart_version
  namespace        = "argocd"
  create_namespace = true
  wait             = true
  timeout          = 900
  values           = [file(local.argocd_values_file), yamlencode(local.argocd_overrides)]
}

resource "helm_release" "roots" {
  name       = "gitops-roots"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  version    = var.argocd_apps_chart_version
  namespace  = "argocd"
  values     = [yamlencode(local.roots_values)]

  depends_on = [helm_release.argocd]
}
