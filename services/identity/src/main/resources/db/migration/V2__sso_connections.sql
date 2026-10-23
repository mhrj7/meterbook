-- Tenant SSO connections (design doc §7.2). The IdP certificate lives in Keycloak only.
CREATE TABLE sso_connections (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL UNIQUE REFERENCES tenants (id) ON DELETE CASCADE, -- one per tenant for now
    keycloak_alias  varchar(64)   NOT NULL UNIQUE,
    protocol        varchar(16)   NOT NULL CHECK (protocol IN ('saml')),
    display_name    varchar(100)  NOT NULL,
    idp_entity_id   varchar(1024) NOT NULL,
    sso_url         varchar(2048) NOT NULL,
    created_at      timestamptz   NOT NULL
);
