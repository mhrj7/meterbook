# ADR-0011: Service mesh mTLS with Istio in ambient mode

- **Status:** Accepted
- **Date:** 2026-10-24
- **Task:** MB-T10
- **Deciders:** Mohit Raj

## Context

Inside the cluster, services talk plain HTTP: identity to Keycloak, and soon every service to identity to verify API
keys (ADR-0010) and to the ledger. NetworkPolicies (ADR-0006) limit *who* may connect, by namespace, but not *what*
connects: any process that lands in a namespace (a compromised dependency, a debug pod) can read and forge that
traffic, and nothing proves which workload is on the other end. MB-T10 requires that plaintext pod-to-pod traffic
is rejected, and every connection carries a verifiable workload identity that later authorization rules can use.

Constraints: small dev nodes, so the per-pod overhead matters; images and charts must not come from registries that
throttle anonymous CI pulls; everything is installed by ArgoCD (ADR-0006).

## Options considered

### Option A — Linkerd
- Pros: simple, light Rust proxy, mTLS on by default.
- Cons: since February 2024 the open-source project publishes only edge releases, which may include breaking changes;
  stable releases are a vendor product. A sidecar per pod.

### Option B — Istio with sidecars
- Pros: the most widely used mesh; full L7 features.
- Cons: an Envoy sidecar per pod (memory, start-up ordering, Jobs that never finish); restarting every pod to join.

### Option C — Istio in ambient mode
- Pros: no sidecars: a per-node `ztunnel` (Rust) carries all traffic of enrolled pods over mTLS (HBONE, port 15008)
  with SPIFFE identities; pods join or leave without restarts; Jobs work unchanged; L7 features can be added later per
  namespace with a waypoint proxy. CNCF graduated, regular supported patch releases.
- Cons: a privileged CNI agent and ztunnel on every node; NetworkPolicies must allow port 15008 and the link-local
  address kubelet probes are rewritten to; ambient is younger than sidecar mode.

## Decision

We will use **Option C**, Istio **1.31.3** in ambient mode, installed by ArgoCD on every cluster.

- **Components:** `base`, `istiod`, `cni` and `ztunnel` charts, from `ghcr.io/istio/release/charts` (OCI). From 1.31
  Istio publishes images to Docker Hub only, so images come from `mirror.gcr.io/istio`, Google's Docker Hub mirror,
  as for our other Docker Hub images.
- **Strict mTLS:** a mesh-wide `PeerAuthentication` in `istio-system` with `mtls.mode: STRICT`. An enrolled pod
  accepts only mTLS from other mesh workloads; plaintext is rejected even where a NetworkPolicy would allow it.
- **Enrollment:** each application namespace (`meterbook-<env>`) carries `istio.io/dataplane-mode: ambient`, set in
  Git by the `platform-mesh` chart (not by hand, never deleted by a prune). Platform namespaces (ArgoCD, External
  Secrets, kube-system) stay outside the mesh.
- **NetworkPolicies** of every workload (the shared library chart) also allow port 15008 from the same peers as the
  service port, and kubelet probes from `169.254.7.127/32`, as ambient requires. NetworkPolicy and mTLS are two
  independent layers: one says who may connect, the other proves who did and encrypts it.
- **Resources** are trimmed for small nodes and set explicitly on every component.
- **Upgrades:** patch releases follow Istio's security announcements; the chart version is pinned exactly (GitOps
  policy) and changed in one place per environment.

## Consequences

- **Positive:** all traffic between application pods is encrypted and mutually authenticated without code changes;
  plaintext into the mesh fails; each connection has a workload identity (`spiffe://cluster.local/ns/<ns>/sa/<sa>`)
  for future `AuthorizationPolicy` rules.
- **Negative / accepted trade-offs:** a privileged DaemonSet pair per node; a mesh outage on a node stops its
  enrolled pods' traffic (fails closed); one more component to patch.
- **Follow-up work:** `AuthorizationPolicy` per service (only identity's callers reach `/v1/api-key`) once more
  services exist; egress policy (which external hosts each namespace may reach); mesh dashboards with MB-T14.
