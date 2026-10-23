package com.meterbook.identity.web;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deny by default: only the health probes and the management port are open; every API route needs
 * credentials.
 */
@Configuration
public class SecurityConfig {

  @Bean
  RequestIds requestIds() {
    return new RequestIds(Clock.systemUTC(), new SecureRandom());
  }

  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter(RequestIds ids) {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter(ids));
    // Before Spring Security, so 401 and 403 responses carry the ID too.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  ApiErrors apiErrors(JsonMapper json) {
    return new ApiErrors(json);
  }

  @Bean
  SecurityFilterChain api(HttpSecurity http, ApiErrors errors) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/livez", "/readyz")
                    .permitAll()
                    // Actuator is served only on the management port, which the Service never
                    // exposes.
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(errors.unauthorized())
                    .accessDeniedHandler(errors.forbidden()))
        .build();
  }
}
