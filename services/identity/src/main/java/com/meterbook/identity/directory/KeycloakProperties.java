package com.meterbook.identity.directory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the identity service reaches Keycloak's admin API, validated at start-up.
 *
 * @param baseUrl internal URL of Keycloak, e.g. {@code http://keycloak:8080}
 * @param timeout connect and read timeout for every call (design doc §7.3)
 */
@Validated
@ConfigurationProperties("identity.keycloak")
public record KeycloakProperties(
    @NotBlank String baseUrl,
    @NotBlank String realm,
    @NotBlank String clientId,
    @NotBlank String clientSecret,
    @NotNull Duration timeout) {}
