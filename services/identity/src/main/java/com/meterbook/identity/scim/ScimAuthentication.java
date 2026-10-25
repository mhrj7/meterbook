package com.meterbook.identity.scim;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated by a tenant's SCIM token. */
public final class ScimAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final UUID tenantId;
  private final UUID tokenId;

  ScimAuthentication(UUID tenantId, UUID tokenId) {
    super(List.of(new SimpleGrantedAuthority("SCIM_CLIENT")));
    this.tenantId = tenantId;
    this.tokenId = tokenId;
    setAuthenticated(true);
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID tokenId() {
    return tokenId;
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public Object getPrincipal() {
    return tokenId;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ScimAuthentication that
        && tenantId.equals(that.tenantId)
        && tokenId.equals(that.tokenId);
  }

  @Override
  public int hashCode() {
    return tenantId.hashCode() * 31 + tokenId.hashCode();
  }
}
