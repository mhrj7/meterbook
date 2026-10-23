package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

// Tests disable metrics export by default; this test is about the Prometheus endpoint.
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ManagementPortTest {

  @LocalServerPort int apiPort;
  @LocalManagementPort int managementPort;

  private static HttpStatusCode status(String url) {
    return RestClient.create()
        .get()
        .uri(url)
        .exchange((request, response) -> response.getStatusCode());
  }

  @Test
  void metrics_are_scrapeable_on_the_management_port_without_credentials() {
    assertThat(status("http://localhost:" + managementPort + "/actuator/prometheus").value())
        .isEqualTo(200);
  }

  @Test
  void actuator_is_not_served_on_the_api_port() {
    assertThat(status("http://localhost:" + apiPort + "/actuator/prometheus").value())
        .isEqualTo(401);
  }
}
