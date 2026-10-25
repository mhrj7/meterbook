package com.meterbook.identity.audit;

import com.meterbook.identity.tenancy.TenantScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AuditInterceptor} first, so it sees every refusal, including later interceptors'. */
@Configuration
public class AuditConfig implements WebMvcConfigurer {

  private final AuditLog audit;
  private final TenantScope scope;

  public AuditConfig(AuditLog audit, TenantScope scope) {
    this.audit = audit;
    this.scope = scope;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AuditInterceptor(audit, scope)).order(Integer.MIN_VALUE);
  }
}
