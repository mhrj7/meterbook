# syntax=docker/dockerfile:1.17
# db-bootstrap: creates each service's database role and database (idempotent). The only place the RDS master
# credentials are ever read.
FROM mirror.gcr.io/library/alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
RUN apk add --no-cache postgresql17-client aws-cli jq bash
COPY --chmod=0555 bootstrap.sh /usr/local/bin/bootstrap.sh
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/bootstrap.sh"]
