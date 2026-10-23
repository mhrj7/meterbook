package com.meterbook.identity.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One PostgreSQL 17 for the whole test run (the version RDS runs), shared by every context. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "mirror.gcr.io/library/postgres:17.11-alpine@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
          .asCompatibleSubstituteFor("postgres");

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

  static {
    POSTGRES.start();
  }

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }
}
