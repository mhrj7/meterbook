# syntax=docker/dockerfile:1.17
# db-bootstrap: creates each service's database role and database (idempotent). The only place the RDS master
# credentials are ever read. curl is included for in-cluster verification (tests/infra/verify-dev.sh).
FROM mirror.gcr.io/library/alpine:3.24@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
RUN apk add --no-cache postgresql17-client aws-cli jq bash curl
COPY --chmod=0555 bootstrap.sh /usr/local/bin/bootstrap.sh
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/bootstrap.sh"]
