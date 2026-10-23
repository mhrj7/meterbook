package com.meterbook.identity.directory;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires the Keycloak-backed {@link Directory}. */
@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
public class DirectoryConfig {

  @Bean
  Directory directory(RestClient.Builder builder, KeycloakProperties properties, Clock clock) {
    return new KeycloakDirectory(builder, properties, clock);
  }
}
