# syntax=docker/dockerfile:1.17
# Meterbook's Keycloak (ADR-0007): the official image, pre-built for PostgreSQL with health and metrics on, so it
# starts with --optimized and never writes to its own install directory (the root filesystem is read-only).
ARG KEYCLOAK=quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b

FROM ${KEYCLOAK} AS build
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_FEATURES=organization
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK}
COPY --from=build /opt/keycloak/ /opt/keycloak/
USER 1000
EXPOSE 8080 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--optimized"]
