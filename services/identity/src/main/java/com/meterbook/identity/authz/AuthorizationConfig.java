package com.meterbook.identity.authz;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthorizationInterceptor} after the audit interceptor, so every refusal it makes
 * is recorded (ADR-0013).
 */
@Configuration
public class AuthorizationConfig implements WebMvcConfigurer {

  private final RoleBindings bindings;

  public AuthorizationConfig(RoleBindings bindings) {
    this.bindings = bindings;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AuthorizationInterceptor(bindings)).order(0);
  }
}
