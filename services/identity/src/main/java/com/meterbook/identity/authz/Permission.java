package com.meterbook.identity.authz;

/**
 * What a caller may do (ADR-0014). Every endpoint authorized by an access token requires exactly
 * one of these, declared with {@link Requires}; the contract names the same one ({@code
 * x-authorization}). {@link #TENANTS_CREATE} belongs to platform operators; the rest to tenant
 * roles.
 */
public enum Permission {
  TENANTS_CREATE("tenants:create"),
  TENANTS_READ("tenants:read"),
  SSO_CONNECTIONS_READ("sso_connections:read"),
  SSO_CONNECTIONS_CREATE("sso_connections:create"),
  SSO_CONNECTIONS_DELETE("sso_connections:delete"),
  PROJECTS_READ("projects:read"),
  PROJECTS_CREATE("projects:create"),
  PROJECTS_UPDATE("projects:update"),
  ENVIRONMENTS_READ("environments:read"),
  ENVIRONMENTS_CREATE("environments:create"),
  API_KEYS_READ("api_keys:read"),
  API_KEYS_CREATE("api_keys:create"),
  API_KEYS_REVOKE("api_keys:revoke"),
  API_KEYS_ROTATE("api_keys:rotate"),
  SCIM_TOKENS_READ("scim_tokens:read"),
  SCIM_TOKENS_CREATE("scim_tokens:create"),
  SCIM_TOKENS_REVOKE("scim_tokens:revoke"),
  ROLES_READ("roles:read"),
  ROLE_BINDINGS_READ("role_bindings:read"),
  ROLE_BINDINGS_CREATE("role_bindings:create"),
  ROLE_BINDINGS_DELETE("role_bindings:delete"),
  AUDIT_EVENTS_READ("audit_events:read");

  private final String code;

  Permission(String code) {
    this.code = code;
  }

  /** The name in the contract and in responses, for example {@code projects:create}. */
  public String code() {
    return code;
  }

  public boolean isRead() {
    return code.endsWith(":read");
  }

  /** Held by platform operators instead of tenant roles. */
  public boolean isPlatform() {
    return this == TENANTS_CREATE;
  }
}
