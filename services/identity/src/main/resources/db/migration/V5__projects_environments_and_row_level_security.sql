-- MB-T08 (ADR-0009): projects and environments, and row-level security on every table that holds tenant data.

CREATE TABLE projects (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    slug        varchar(40)   NOT NULL CHECK (slug = lower(slug)),
    name        varchar(200)  NOT NULL,
    created_at  timestamptz   NOT NULL,
    UNIQUE (tenant_id, slug),
    UNIQUE (tenant_id, id)                              -- target of the environments foreign key below
);

CREATE TABLE environments (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL,
    project_id  uuid          NOT NULL,
    slug        varchar(40)   NOT NULL CHECK (slug = lower(slug)),
    name        varchar(200)  NOT NULL,
    kind        varchar(8)    NOT NULL CHECK (kind IN ('live', 'test')),
    created_at  timestamptz   NOT NULL,
    UNIQUE (project_id, slug),
    -- An environment always belongs to its project's tenant.
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE
);

-- Group memberships carry their tenant too, so row-level security applies to them like to every other table.
ALTER TABLE scim_group_members ADD COLUMN tenant_id uuid;
/* [jooq ignore start] */
UPDATE scim_group_members m SET tenant_id = g.tenant_id FROM scim_groups g WHERE g.id = m.group_id;
/* [jooq ignore stop] */
ALTER TABLE scim_group_members ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE scim_groups ADD CONSTRAINT scim_groups_tenant_id_id_key UNIQUE (tenant_id, id);
ALTER TABLE scim_users ADD CONSTRAINT scim_users_tenant_id_id_key UNIQUE (tenant_id, id);
/* [jooq ignore start] */
ALTER TABLE scim_group_members
    DROP CONSTRAINT scim_group_members_group_id_fkey,
    DROP CONSTRAINT scim_group_members_user_id_fkey,
    ADD CONSTRAINT scim_group_members_group_fkey FOREIGN KEY (tenant_id, group_id) REFERENCES scim_groups (tenant_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT scim_group_members_user_fkey FOREIGN KEY (tenant_id, user_id) REFERENCES scim_users (tenant_id, id) ON DELETE CASCADE;
/* [jooq ignore stop] */

/* [jooq ignore start] */
-- The tenant of the current transaction (TenantScope.inTenant), or NULL. NULL matches no row: fail closed.
CREATE FUNCTION app_tenant() RETURNS uuid LANGUAGE sql STABLE AS
    $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- True only inside TenantScope.asPlatform: the few audited operations that look across tenants.
CREATE FUNCTION app_platform() RETURNS boolean LANGUAGE sql STABLE AS
    $$ SELECT coalesce(current_setting('app.scope', true) = 'platform', false) $$;

-- Tenant-only tables. FORCE: the service's role owns these tables, and owners bypass RLS unless it is forced.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['sso_connections', 'scim_users', 'scim_groups', 'scim_group_members', 'projects', 'environments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant())', t);
    END LOOP;
END $$;

-- Tables the platform scope may also use: tenant creation and domain checks, SCIM token resolution.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = app_tenant() OR app_platform()) WITH CHECK (id = app_tenant() OR app_platform());

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_domains', 'scim_tokens'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_tenant() OR app_platform()) WITH CHECK (tenant_id = app_tenant() OR app_platform())', t);
    END LOOP;
END $$;
/* [jooq ignore stop] */
