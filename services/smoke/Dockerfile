# The platform smoke service (deploy/services/smoke): podinfo, re-published by this pipeline so it is scanned, given an
# SBOM and signed like every image we run (ADR-0012). podinfo's own signatures use a format the cluster's admission
# controller cannot read yet. Bump the digest to follow upstream releases.
FROM ghcr.io/stefanprodan/podinfo:6.15.0@sha256:ec73780a8425f59ea49f5bc8cdff0d598805a224fbaa1f86c67a244f250fa9da
