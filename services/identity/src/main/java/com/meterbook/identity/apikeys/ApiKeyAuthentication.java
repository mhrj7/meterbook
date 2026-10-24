package com.meterbook.identity.apikeys;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated by an environment API key. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final transient ApiKeys.Holder holder;

  ApiKeyAuthentication(ApiKeys.Holder holder) {
    super(List.of(new SimpleGrantedAuthority("API_KEY")));
    this.holder = holder;
    setAuthenticated(true);
  }

  public ApiKeys.Holder holder() {
    return holder;
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public Object getPrincipal() {
    return holder.keyId();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ApiKeyAuthentication that && holder.equals(that.holder);
  }

  @Override
  public int hashCode() {
    return holder.hashCode();
  }
}
