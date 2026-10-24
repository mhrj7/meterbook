#!/usr/bin/env bash
# Creates a login role and a database owned by it for each service, idempotently, and (re)sets each role's
# password so a changed password takes effect on the next run.
#
# Environment:
#   DB_HOST, DB_PORT        where the database is (from the meterbook-<env>/database secret)
#   MASTER_SECRET_ARN       Secrets Manager secret with the RDS master credentials ({"username","password"})
#   MASTER_USERNAME/MASTER_PASSWORD   instead of MASTER_SECRET_ARN, for local tests only
#   DATABASES               space-separated <database>:<role>:<PASSWORD_VARIABLE> triples
#   PGSSLMODE               default verify-full against the RDS CA bundle; "disable" for local tests
set -euo pipefail

log() { printf '{"level":"%s","message":"%s"}\n' "$1" "$2"; }

: "${DB_HOST:?}" "${DB_PORT:?}" "${DATABASES:?}"
if [ -n "${MASTER_SECRET_ARN:-}" ]; then
  master=$(aws secretsmanager get-secret-value --secret-id "$MASTER_SECRET_ARN" --query SecretString --output text)
  MASTER_USERNAME=$(jq -r .username <<<"$master")
  MASTER_PASSWORD=$(jq -r .password <<<"$master")
  unset master
fi
: "${MASTER_USERNAME:?set MASTER_SECRET_ARN or MASTER_USERNAME}" "${MASTER_PASSWORD:?}"

export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGUSER="$MASTER_USERNAME" PGPASSWORD="$MASTER_PASSWORD" PGDATABASE=postgres
export PGSSLMODE="${PGSSLMODE:-require}" PGCONNECT_TIMEOUT=10

# Wait for the database (it may still be starting when the environment is new).
for attempt in $(seq 1 30); do
  psql -qtAc 'select 1' >/dev/null 2>&1 && break
  [ "$attempt" -eq 30 ] && { log error "database not reachable at $DB_HOST:$DB_PORT"; exit 1; }
  sleep 5
done

for entry in $DATABASES; do
  IFS=: read -r database role password_variable <<<"$entry"
  password="${!password_variable:?$password_variable is not set}"
  # Identifiers and the password are quoted by format(%I/%L) inside PostgreSQL, never by the shell.
  psql -v ON_ERROR_STOP=1 -qtA \
    -v role="$role" -v database="$database" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'role', :'password') \gexec
-- PostgreSQL 16+: handing a database to a role needs SET on that role. Creating it gave only ADMIN; take SET
-- without INHERIT, so the master user never acquires the role's privileges.
SELECT format('GRANT %I TO current_user WITH SET TRUE, INHERIT FALSE', :'role') WHERE NOT pg_has_role(current_user, :'role', 'SET') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'database', :'role') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'database', :'role') \gexec
-- Only the owner can revoke; act as the owner for this one statement.
SELECT format('SET ROLE %I', :'role') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'database') \gexec
RESET ROLE;
SQL
  log info "database $database ready for role $role"
done
