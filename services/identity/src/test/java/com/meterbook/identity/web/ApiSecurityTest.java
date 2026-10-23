package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityTest {

  private static final String ID_PATTERN = "req_[0-9A-HJKMNP-TV-Z]{26}";

  @Autowired MockMvc mvc;

  @Test
  void liveness_and_readiness_probes_are_open() throws Exception {
    mvc.perform(get("/livez")).andExpect(status().isOk());
    mvc.perform(get("/readyz")).andExpect(status().isOk());
  }

  @Test
  void every_other_route_requires_credentials_with_the_standard_error_shape() throws Exception {
    mvc.perform(get("/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.type").value("authentication_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_credentials"))
        .andExpect(
            jsonPath("$.error.request_id").value(org.hamcrest.Matchers.matchesPattern(ID_PATTERN)))
        .andExpect(jsonPath("$.error.param").doesNotExist());
  }

  @Test
  void actuator_endpoints_are_not_served_on_the_api_port() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void every_response_carries_a_generated_request_id() throws Exception {
    mvc.perform(get("/livez"))
        .andExpect(header().string("Request-Id", org.hamcrest.Matchers.matchesPattern(ID_PATTERN)));
  }

  @Test
  void reuses_a_well_formed_request_id_from_the_caller() throws Exception {
    String id = "req_01JBZ8K6S9M2Q4T7V0X3Y5A8C1";
    mvc.perform(get("/v1/me").header("Request-Id", id))
        .andExpect(header().string("Request-Id", id))
        .andExpect(jsonPath("$.error.request_id").value(id));
  }

  @Test
  void replaces_a_malformed_request_id_from_the_caller() throws Exception {
    String injected = "req_x\"} forged log line";
    String returned =
        mvc.perform(get("/livez").header("Request-Id", injected))
            .andReturn()
            .getResponse()
            .getHeader("Request-Id");

    assertThat(returned).isNotEqualTo(injected).matches(ID_PATTERN);
  }

  @Test
  void logs_are_json_lines_with_the_service_name(CapturedOutput output) {
    org.slf4j.LoggerFactory.getLogger(ApiSecurityTest.class).info("probe");

    assertThat(output.getOut().lines().filter(l -> l.contains("\"probe\"")).findFirst())
        .hasValueSatisfying(
            line -> assertThat(line).startsWith("{").contains("\"level\":\"INFO\""));
  }
}
