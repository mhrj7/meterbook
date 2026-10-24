package com.meterbook.identity.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** An authenticated {@link Caller}; its authorities are the caller's roles. */
public final class CallerAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final transient Caller caller;
  private final transient Jwt token;

  CallerAuthentication(Caller caller, Jwt token) {
    super(
        caller.roles().stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList());
    this.caller = caller;
    this.token = token;
    setAuthenticated(true);
  }

  public Caller caller() {
    return caller;
  }

  @Override
  public Object getCredentials() {
    return token;
  }

  @Override
  public Object getPrincipal() {
    return caller;
  }

  @Override
  public String getName() {
    return caller.userId().toString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CallerAuthentication that
        && super.equals(that)
        && caller.equals(that.caller);
  }

  @Override
  public int hashCode() {
    return List.of(super.hashCode(), caller.hashCode()).hashCode();
  }

  /**
   * Sessions are stateless: an authentication is never serialized, so refuse to rebuild one without
   * its fields.
   */
  private void readObject(java.io.ObjectInputStream in) throws java.io.NotSerializableException {
    throw new java.io.NotSerializableException(getClass().getName());
  }
}
