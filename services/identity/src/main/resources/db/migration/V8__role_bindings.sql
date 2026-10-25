-- MB-T11 (ADR-0014): tenant roles, bound to a user or to a SCIM group of the tenant. The only source of tenant roles.

CREATE TABLE role_bindings (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    role        varchar(16)  NOT NULL CHECK (role IN ('admin', 'developer', 'viewer', 'auditor')),
    user_id     uuid,                                  -- a Keycloak user, member of the tenant's organization
    group_id    uuid,                                  -- a SCIM group of the tenant; its bindings go with it
    created_at  timestamptz  NOT NULL,
    UNIQUE (tenant_id, role, user_id),                 -- NULLs are distinct: a group binding never conflicts here
    UNIQUE (tenant_id, role, group_id)
);

CREATE INDEX role_bindings_user ON role_bindings (tenant_id, user_id);
CREATE INDEX role_bindings_group ON role_bindings (group_id);

-- One-off data jobs that need more than SQL (they call Keycloak), and whether they are done.
CREATE TABLE backfills (
    name          varchar(64)  PRIMARY KEY,
    completed_at  timestamptz  NOT NULL
);

/* [jooq ignore start] */
ALTER TABLE role_bindings
    ADD CONSTRAINT role_bindings_one_subject CHECK (num_nonnulls(user_id, group_id) = 1),
    ADD CONSTRAINT role_bindings_group_fkey FOREIGN KEY (tenant_id, group_id)
        REFERENCES scim_groups (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE role_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_bindings
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());

-- The backfill of tenant admins from Keycloak acts as the system, not as a person.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_actor_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_actor_type_check
    CHECK (actor_type IN ('user', 'scim_token', 'platform_operator', 'system'));
/* [jooq ignore stop] */
