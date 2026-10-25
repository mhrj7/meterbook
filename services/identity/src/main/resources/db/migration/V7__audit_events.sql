-- MB-T11 (ADR-0013): the customer-facing audit log. Append-only: tenants may read and add their own events, nobody
-- may change or remove one. No foreign key to tenants: removing a tenant must never silently remove its evidence.

CREATE TABLE audit_events (
    id           uuid          PRIMARY KEY,
    tenant_id    uuid          NOT NULL,
    occurred_at  timestamptz   NOT NULL,
    action       varchar(64)   NOT NULL,
    outcome      varchar(16)   NOT NULL CHECK (outcome IN ('success', 'denied', 'not_found')),
    actor_type   varchar(32)   NOT NULL CHECK (actor_type IN ('user', 'scim_token', 'platform_operator')),
    actor_id     uuid          NOT NULL,
    target_type  varchar(32)   NOT NULL,
    target_id    uuid,                                 -- null when the request named no valid ID
    request_id   varchar(64)   NOT NULL,
    details      jsonb         NOT NULL
);

-- Newest first, the order of GET /v1/audit-events and its cursor.
CREATE INDEX audit_events_tenant_time ON audit_events (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX audit_events_tenant_target ON audit_events (tenant_id, target_id, occurred_at DESC, id DESC);

/* [jooq ignore start] */
ALTER TABLE audit_events
    ADD CONSTRAINT audit_events_action_format CHECK (action ~ '^[a-z_]+\.[a-z_]+$'),
    ADD CONSTRAINT audit_events_details_object CHECK (jsonb_typeof(details) = 'object');

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
-- Only SELECT and INSERT policies: under FORCE an UPDATE or DELETE matches no row, whoever runs it.
CREATE POLICY audit_read ON audit_events FOR SELECT USING (tenant_id = app_tenant());
CREATE POLICY audit_append ON audit_events FOR INSERT WITH CHECK (tenant_id = app_tenant());

-- And in case a policy is ever added by mistake, or the table owner turns row-level security off: refuse outright.
CREATE FUNCTION audit_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (%)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER audit_events_no_update_or_delete BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();
CREATE TRIGGER audit_events_no_truncate BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_append_only();
/* [jooq ignore stop] */
