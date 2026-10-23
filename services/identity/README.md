# identity

Tenants, enterprise SSO connections and SCIM 2.0 provisioning (MB-T07). Design: [docs/design/identity.md](../../docs/design/identity.md),
decisions: [ADR-0007](../../docs/adr/0007-identity-keycloak-broker-and-scim.md), stack: [ADR-0008](../../docs/adr/0008-java-service-stack.md),
API: [contracts/openapi/identity-v1.yaml](../../contracts/openapi/identity-v1.yaml).

## Build and test

Requires JDK 25 (the Gradle toolchain checks it).

```bash
./gradlew check            # Spotless, Error Prone, tests, coverage gate (90% line / 85% branch)
./gradlew spotlessApply    # format
./gradlew dependencies --write-locks   # after changing a dependency
docker build -t meterbook-identity .   # image (distroless, non-root)
```

## Ports

| Port | Serves |
|---|---|
| 8080 | API, `/livez`, `/readyz` |
| 9090 | Actuator (`/actuator/prometheus`, `/actuator/health`); never exposed by the Service |
