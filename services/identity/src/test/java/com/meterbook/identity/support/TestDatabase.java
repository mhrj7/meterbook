package com.meterbook.identity.support;

import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 17 for the whole test run (the version RDS runs), shared by every context. The
 * service connects as an ordinary role, as on RDS: a superuser ignores row-level security, so tests
 * on one would prove nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "mirror.gcr.io/library/postgres:17.11-alpine@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
          .asCompatibleSubstituteFor("postgres");

  // Each cached Spring context holds its own pool; only superusers may use the reserved slots.
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE).withCommand("postgres", "-c", "max_connections=500");
  public static final String APP_ROLE = "identity_app";
  static final String APP_PASSWORD = "identity-app-password";

  static {
    POSTGRES.start();
    try {
      var result =
          POSTGRES.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              POSTGRES.getUsername(),
              "-d",
              "postgres",
              "-c",
              "CREATE ROLE "
                  + APP_ROLE
                  + " LOGIN PASSWORD '"
                  + APP_PASSWORD
                  + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE",
              // CREATE DATABASE cannot run inside the transaction a multi-statement -c would use.
              "-c",
              "CREATE DATABASE identity OWNER " + APP_ROLE + ";");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "could not create the application role: " + result.getStderr());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** JDBC URL of the identity database. */
  public static String url() {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(5432)
        + "/identity";
  }

  @Bean
  DynamicPropertyRegistrar database() {
    return registry -> {
      registry.add("spring.datasource.url", TestDatabase::url);
      registry.add("spring.datasource.username", () -> APP_ROLE);
      registry.add("spring.datasource.password", () -> APP_PASSWORD);
    };
  }
}
