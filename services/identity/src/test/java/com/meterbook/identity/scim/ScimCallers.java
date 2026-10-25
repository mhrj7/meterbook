package com.meterbook.identity.scim;

import java.util.UUID;

/** SCIM clients for tests outside this package. */
public final class ScimCallers {

  private ScimCallers() {}

  public static ScimAuthentication of(ScimTokens.Holder holder) {
    return new ScimAuthentication(holder.tenantId(), holder.tokenId());
  }

  public static ScimAuthentication of(UUID tenant, UUID token) {
    return new ScimAuthentication(tenant, token);
  }
}
