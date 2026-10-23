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

  argocd_values = {
    configs = {
      params = {
        # TLS terminates at kubectl port-forward / a future ingress; the server is never exposed publicly.
        "server.insecure" = true
      }
      cm = {
        "timeout.reconciliation" = var.reconciliation_timeout
      }
    }
    dex           = { enabled = false }
    notifications = { enabled = false }
    server = {
      service = { type = "ClusterIP" }
    }
    controller = {
      resources = { requests = { cpu = "100m", memory = "256Mi" }, limits = { memory = "1Gi" } }
    }
    repoServer = {
      resources = { requests = { cpu = "50m", memory = "128Mi" }, limits = { memory = "512Mi" } }
    }
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
  values           = [yamlencode(local.argocd_values)]
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
