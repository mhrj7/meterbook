-- MB-T09 (ADR-0010): environment-scoped API keys. Only a SHA-256 of each key is stored.

-- Target of the api_keys foreign key below: a key's tenant, project and environment always agree.
ALTER TABLE environments ADD CONSTRAINT environments_tenant_id_project_id_id_key UNIQUE (tenant_id, project_id, id);

CREATE TABLE api_keys (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL,
    project_id      uuid          NOT NULL,
    environment_id  uuid          NOT NULL,
    kind            varchar(4)    NOT NULL CHECK (kind IN ('live', 'test')),  -- the environment's, kept for lookups
    name            varchar(200)  NOT NULL,
    prefix          varchar(12)   NOT NULL,
    key_hash        char(64)      NOT NULL UNIQUE,
    scopes          text[]        NOT NULL CHECK (cardinality(scopes) > 0),
    created_at      timestamptz   NOT NULL,
    expires_at      timestamptz,
    revoked_at      timestamptz,
    last_used_at    timestamptz,
    rotated_to      uuid          REFERENCES api_keys (id),
    FOREIGN KEY (tenant_id, project_id, environment_id)
        REFERENCES environments (tenant_id, project_id, id) ON DELETE CASCADE
);

CREATE INDEX api_keys_live_by_environment ON api_keys (environment_id, id) WHERE revoked_at IS NULL;

/* [jooq ignore start] */
-- Resolving a presented key to its tenant is a platform operation, as for SCIM tokens (ADR-0009).
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_keys
    USING (tenant_id = app_tenant() OR app_platform()) WITH CHECK (tenant_id = app_tenant() OR app_platform());
/* [jooq ignore stop] */
