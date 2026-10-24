package com.meterbook.identity.scim;

import com.meterbook.identity.web.BearerTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /scim/v2} accepts tenant SCIM tokens only (not OAuth tokens), with SCIM's error format. It
 * is checked before the API chain, which covers everything else.
 */
@Configuration
public class ScimSecurity {

  @Bean
  @Order(1)
  SecurityFilterChain scim(HttpSecurity http, ScimTokens tokens, JsonMapper json) throws Exception {
    ScimErrors errors = new ScimErrors(json);
    return http.securityMatcher("/scim/v2/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .addFilterBefore(new TokenFilter(tokens), AuthorizationFilter.class)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (request, response, ex) -> {
                      response.setHeader("WWW-Authenticate", "Bearer realm=\"meterbook-scim\"");
                      errors.write(response, 401, null, "missing, unknown or revoked SCIM token");
                    }))
        .build();
  }

  /** Authenticates {@code Authorization: Bearer mbscim_…}; anything else stays anonymous (401). */
  static final class TokenFilter extends OncePerRequestFilter {
    private final ScimTokens tokens;

    TokenFilter(ScimTokens tokens) {
      this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      // Authenticated only by a live token in the database; the header alone decides nothing.
      BearerTokens.of(request)
          .flatMap(tokens::authenticate)
          .ifPresent(
              holder -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                    new ScimAuthentication(holder.tenantId(), holder.tokenId()));
                SecurityContextHolder.setContext(context);
              });
      chain.doFilter(request, response);
    }
  }
}
