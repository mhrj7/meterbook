-- Identity schema, owned by the identity service (docs/design/identity.md §7.2). Migrations are additive only.

CREATE TABLE tenants (
    id          uuid         PRIMARY KEY,              -- the Keycloak organization ID
    slug        varchar(40)  NOT NULL UNIQUE CHECK (slug = lower(slug)),
    name        varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL
);

-- One tenant per email domain: the primary key is what stops a tenant claiming another company's users.
CREATE TABLE tenant_domains (
    domain      varchar(253) PRIMARY KEY CHECK (domain = lower(domain)),
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX tenant_domains_tenant_id ON tenant_domains (tenant_id);

-- Idempotency-Key records (api-standards.md): one per caller and key, kept 24 hours.
CREATE TABLE idempotency_keys (
    scope           varchar(64)  NOT NULL,             -- the caller's user ID
    key             varchar(255) NOT NULL,
    request_hash    char(64)     NOT NULL,             -- SHA-256 of method, path and body
    status_code     integer,                           -- null while the first request is in flight
    response_body   text,
    location        varchar(2048),
    created_at      timestamptz  NOT NULL,
    PRIMARY KEY (scope, key)
);

CREATE INDEX idempotency_keys_created_at ON idempotency_keys (created_at);
