package com.meterbook.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Tenants, enterprise SSO connections and SCIM provisioning for Meterbook (ADR-0007). */
@SpringBootApplication
public class IdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}
