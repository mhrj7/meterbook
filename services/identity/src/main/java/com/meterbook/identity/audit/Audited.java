package com.meterbook.identity.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint for the audit log (ADR-0013). A refused call (403 or 404) is recorded by {@link
 * AuditInterceptor}; a successful change is recorded by the service, in its own transaction.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

  AuditAction value();

  /** The path variable naming what the request is about; empty when the path names nothing. */
  String targetVariable() default "id";

  /** The type of {@link #targetVariable}, when it is not the action's own (a parent resource). */
  String targetType() default "";
}
