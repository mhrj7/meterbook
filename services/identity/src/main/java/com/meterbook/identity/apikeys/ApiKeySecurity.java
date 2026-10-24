package com.meterbook.identity.apikeys;

import com.meterbook.identity.web.ApiErrors;
import com.meterbook.identity.web.BearerTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /v1/api-key} accepts API keys only. Every other API route takes access tokens, so a key
 * can never manage keys, users or tenants (ADR-0010).
 */
@Configuration
public class ApiKeySecurity {

  @Bean
  @Order(1)
  SecurityFilterChain apiKey(HttpSecurity http, ApiKeys keys, ApiErrors errors) throws Exception {
    return http.securityMatcher("/v1/api-key")
        // Only a key: a signed-in person's authentication is not one, and is answered like no key.
        .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("API_KEY"))
        .addFilterBefore(new KeyFilter(keys), AuthorizationFilter.class)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        // Verdicts are cached by callers (Cache-Control on the 200); a 401 must never be.
        .headers(headers -> headers.cacheControl(cache -> cache.disable()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(errors.unauthorized())
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            errors
                                .unauthorized()
                                .commence(
                                    request,
                                    response,
                                    new InsufficientAuthenticationException(
                                        "an API key is required"))))
        .build();
  }

  /** Authenticates {@code Authorization: Bearer mb_…}; anything else stays anonymous (401). */
  static final class KeyFilter extends OncePerRequestFilter {
    private final ApiKeys keys;

    KeyFilter(ApiKeys keys) {
      this.keys = keys;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      response.setHeader("Cache-Control", "no-store");
      // Authenticated only by a live key in the database; the header alone decides nothing.
      BearerTokens.of(request)
          .flatMap(keys::authenticate)
          .ifPresent(
              holder -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new ApiKeyAuthentication(holder));
                SecurityContextHolder.setContext(context);
              });
      chain.doFilter(request, response);
    }
  }
}
