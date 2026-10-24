-- SCIM 2.0 resources (design doc §7.2). SCIM is the source of truth for provisioned users; each row maps to one
-- Keycloak user. userName and displayName are case-insensitive in SCIM, so a lower-case key carries the uniqueness.
CREATE TABLE scim_users (
    id                uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_name         varchar(255)  NOT NULL,
    user_name_key     varchar(255)  NOT NULL CHECK (user_name_key = lower(user_name_key)),
    external_id       varchar(255),
    keycloak_user_id  uuid          NOT NULL UNIQUE,
    email             varchar(254)  NOT NULL,
    given_name        varchar(100),
    family_name       varchar(100),
    display_name      varchar(200),
    active            boolean       NOT NULL,
    version           bigint        NOT NULL,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    UNIQUE (tenant_id, user_name_key)
);

CREATE INDEX scim_users_external_id ON scim_users (tenant_id, external_id);

CREATE TABLE scim_groups (
    id                uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    display_name      varchar(200)  NOT NULL,
    display_name_key  varchar(200)  NOT NULL CHECK (display_name_key = lower(display_name_key)),
    external_id       varchar(255),
    version           bigint        NOT NULL,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    UNIQUE (tenant_id, display_name_key)
);

CREATE TABLE scim_group_members (
    group_id  uuid  NOT NULL REFERENCES scim_groups (id) ON DELETE CASCADE,
    user_id   uuid  NOT NULL REFERENCES scim_users (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX scim_group_members_user_id ON scim_group_members (user_id);
