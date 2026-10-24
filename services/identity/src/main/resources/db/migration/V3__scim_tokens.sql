-- Credentials for a tenant's SCIM client (design doc §7.2). Only a SHA-256 hash of each token is stored.
CREATE TABLE scim_tokens (
    id            uuid          PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    description   varchar(200)  NOT NULL,
    prefix        varchar(16)   NOT NULL,     -- the first characters, so an admin can recognise a token
    token_hash    char(64)      NOT NULL UNIQUE,
    created_at    timestamptz   NOT NULL,
    last_used_at  timestamptz,
    revoked_at    timestamptz
);

CREATE INDEX scim_tokens_tenant_id ON scim_tokens (tenant_id);
