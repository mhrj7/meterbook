# ADR-0012: Application namespaces admit only signed images (Sigstore policy-controller)

- **Status:** Accepted
- **Date:** 2026-10-24
- **Task:** MB-T05, MB-T06 (follow-up promised by ADR-0003 and ADR-0006)
- **Deciders:** Mohit Raj

## Context

CI builds every image once, scans it, and on `main` signs it with keyless cosign using the workflow's GitHub OIDC
identity (ADR-0003). Nothing checked those signatures where it matters: when a pod is created. Anyone able to create a
pod in an application namespace, through a compromised CI token, a mistyped digest or a hand-applied manifest, could
run any image, and the signatures proved nothing. ADR-0003 and ADR-0006 named admission-time verification as the
follow-up.

Constraints: verification must be keyless (no signing keys to store or rotate); the policy must name *which* workflow
signed an image, not only that some signature exists; platform components (Istio, External Secrets, ArgoCD) are
third-party images we do not sign and live in their own namespaces; everything is installed by ArgoCD.

## Options considered

### Option A — Kyverno `verifyImages`
- Pros: a general policy engine, also useful for other rules.
- Cons: a large component to run for one rule; its broader policy surface is not needed today.

### Option B — Sigstore policy-controller
- Pros: built by the Sigstore project for exactly this; keyless identities (issuer + subject) as first-class policy;
  namespaces opt in by label; images that match no policy can be refused; small footprint.
- Cons: one more webhook in the admission path (`failurePolicy: Fail`), so it must be available for pods to start in
  application namespaces.

### Option C — Verify in CI only
- Pros: nothing in the cluster.
- Cons: does not cover anything applied outside the pipeline, which is the case it is meant for.

## Decision

We will use **Option B**, Sigstore policy-controller (chart 0.10.8, controller 0.13.1), installed by ArgoCD in
`cosign-system` on every cluster.

- **Opt-in by namespace:** application namespaces carry `policy.sigstore.dev/include: "true"`, set by the
  `platform-policy` chart together with the mesh label (ADR-0011). Both live in one chart because ArgoCD applies every
  chart with the same field manager, so two charts labelling one namespace would remove each other's labels.
- **Deny by default:** `no-match-policy: deny`. An image that matches no `ClusterImagePolicy` is refused.
- **Policies** (keyless, Fulcio certificates issued to `https://token.actions.githubusercontent.com`, recorded in
  Rekor), each with its subject anchored `^…$`:
  - `ghcr.io/mhrj7/**`: signed by `mhrj7/meterbook/.github/workflows/container.yml@refs/heads/main`. Images built from
    branches, other workflows or other repositories are refused.
  - `ghcr.io/stefanprodan/podinfo**` (the smoke service): signed by podinfo's own `release.yml` at a version tag.
- **Policies match Git exactly:** `mode: enforce` and the authority name are written explicitly, so no field of a
  security policy is excluded from drift detection. Only the controller's own webhook fields it rewrites at runtime
  (CA bundle, service path, namespace selector) are ignored by ArgoCD.
- **Test pods** in application namespaces use our own signed tools image (the db-bootstrap image).
- **Availability:** one replica in dev, two in prod with the chart's PodDisruptionBudget.

## Consequences

- **Positive:** an image runs in an application namespace only if this repository's `main` pipeline (or podinfo's
  release pipeline) signed that exact digest; signatures now have a consumer; a stray manifest cannot run an
  arbitrary image.
- **Negative / accepted trade-offs:** pods in application namespaces cannot start while the webhook is down (fails
  closed); every new third-party image needs a reviewed policy first; verification reaches Fulcio, Rekor and the
  Sigstore TUF root over the network.
- **Known gap:** the chart's `leases-cleanup` Job uses an unpinned image (`cgr.dev/chainguard/kubectl:latest-dev`).
  It is a Helm `post-delete` hook, which ArgoCD does not run, so it never executes in our clusters.
- **Follow-up work:** require SBOM attestations as well as signatures; policies for platform namespaces once their
  images are verifiable (Istio and External Secrets sign theirs).
