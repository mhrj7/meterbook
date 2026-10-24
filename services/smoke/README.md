# smoke

The platform smoke service: [podinfo](https://github.com/stefanprodan/podinfo), pinned by digest and re-published as
`ghcr.io/mhrj7/meterbook-smoke`. CI builds it like any service image, so it is scanned with Trivy, described by an
SBOM and signed by `container.yml` on `main`. Application namespaces admit only images signed that way (ADR-0012).
Deployed by `deploy/services/smoke/<env>.yaml`.
