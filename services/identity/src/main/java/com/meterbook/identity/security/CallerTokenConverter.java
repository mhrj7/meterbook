package com.meterbook.identity.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a validated access token into a {@link CallerAuthentication}. A token must name exactly one
 * tenant, unless it belongs to a platform operator.
 */
public final class CallerTokenConverter implements Converter<Jwt, CallerAuthentication> {

  @Override
  public CallerAuthentication convert(Jwt jwt) {
    Set<Role> roles = TokenClaims.roles(jwt.getClaim("realm_access"));
    Optional<UUID> tenant = TokenClaims.tenant(jwt.getClaim("organization"));
    if (tenant.isEmpty() && !roles.contains(Role.PLATFORM_ADMIN)) {
      throw new InvalidBearerTokenException("the token must name exactly one tenant");
    }
    String idp = jwt.getClaimAsString("idp");
    Caller caller =
        new Caller(
            TokenClaims.subject(jwt.getSubject()),
            tenant,
            Optional.ofNullable(jwt.getClaimAsString("email")).orElse(""),
            Optional.ofNullable(jwt.getClaimAsString("name")).orElse(""),
            roles,
            idp == null ? Caller.LoginMethod.PASSWORD : Caller.LoginMethod.SSO,
            TokenClaims.ssoConnection(idp));
    return new CallerAuthentication(caller, jwt);
  }
}
