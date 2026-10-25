package com.meterbook.identity.audit;

/**
 * Everything the audit log records (ADR-0013). Changes are recorded on success and when refused;
 * reads ({@code .read}) only when refused (403 or 404), so cross-tenant attempts are visible.
 */
public enum AuditAction {
  TENANT_CREATED("tenant.created", "tenant"),
  TENANT_READ("tenant.read", "tenant"),
  SSO_CONNECTION_CREATED("sso_connection.created", "sso_connection"),
  SSO_CONNECTION_DELETED("sso_connection.deleted", "sso_connection"),
  SSO_CONNECTION_READ("sso_connection.read", "sso_connection"),
  PROJECT_CREATED("project.created", "project"),
  PROJECT_UPDATED("project.updated", "project"),
  PROJECT_READ("project.read", "project"),
  ENVIRONMENT_CREATED("environment.created", "environment"),
  ENVIRONMENT_READ("environment.read", "environment"),
  API_KEY_CREATED("api_key.created", "api_key"),
  API_KEY_REVOKED("api_key.revoked", "api_key"),
  API_KEY_ROTATED("api_key.rotated", "api_key"),
  API_KEY_READ("api_key.read", "api_key"),
  SCIM_TOKEN_CREATED("scim_token.created", "scim_token"),
  SCIM_TOKEN_REVOKED("scim_token.revoked", "scim_token"),
  SCIM_USER_CREATED("scim_user.created", "scim_user"),
  SCIM_USER_REPLACED("scim_user.replaced", "scim_user"),
  SCIM_USER_UPDATED("scim_user.updated", "scim_user"),
  SCIM_USER_DELETED("scim_user.deleted", "scim_user"),
  SCIM_USER_READ("scim_user.read", "scim_user"),
  SCIM_GROUP_CREATED("scim_group.created", "scim_group"),
  SCIM_GROUP_UPDATED("scim_group.updated", "scim_group"),
  SCIM_GROUP_DELETED("scim_group.deleted", "scim_group"),
  SCIM_GROUP_READ("scim_group.read", "scim_group"),
  ROLE_BINDING_CREATED("role_binding.created", "role_binding"),
  ROLE_BINDING_DELETED("role_binding.deleted", "role_binding"),
  AUDIT_EVENTS_READ("audit_events.read", "audit_events");

  private final String code;
  private final String targetType;

  AuditAction(String code, String targetType) {
    this.code = code;
    this.targetType = targetType;
  }

  /** The name customers see, for example {@code project.updated}. */
  public String code() {
    return code;
  }

  public String targetType() {
    return targetType;
  }

  /** Whether the action changes something, and so is recorded when it succeeds. */
  public boolean isChange() {
    return !code.endsWith(".read");
  }
}
