package com.meterbook.identity.support;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Keycloak 26 with Meterbook's realm imported from infra/keycloak, exactly as deployed. */
public final class KeycloakContainer extends GenericContainer<KeycloakContainer> {

  public static final String IMAGE =
      "quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b";
  public static final String IDENTITY_CLIENT_SECRET = "identity-test-secret";
  public static final String OPERATOR_CLIENT_SECRET = "operator-test-secret";
  static final String ADMIN_PASSWORD = "admin-test-password";

  private static final Path REALM = Path.of("../../infra/keycloak/realm-meterbook.json");

  public KeycloakContainer() {
    super(IMAGE);
    withExposedPorts(8080, 9000);
    withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
    withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD);
    withEnv("KC_HEALTH_ENABLED", "true");
    withEnv("MB_IDENTITY_CLIENT_SECRET", IDENTITY_CLIENT_SECRET);
    withEnv("MB_OPERATOR_CLIENT_SECRET", OPERATOR_CLIENT_SECRET);
    withCopyFileToContainer(
        MountableFile.forHostPath(REALM), "/opt/keycloak/data/import/realm-meterbook.json");
    withCommand("start-dev", "--import-realm");
    waitingFor(
        Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));
  }

  public String baseUrl() {
    return "http://" + getHost() + ":" + getMappedPort(8080);
  }

  public String issuer() {
    return baseUrl() + "/realms/meterbook";
  }

  public String jwksUri() {
    return issuer() + "/protocol/openid-connect/certs";
  }
}
