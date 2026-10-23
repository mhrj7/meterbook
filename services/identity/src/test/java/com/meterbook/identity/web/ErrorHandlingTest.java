package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.TestDatabase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, ErrorHandlingTest.Fixture.class})
class ErrorHandlingTest {

  @RestController
  static class Things {
    record Thing(@NotBlank @Size(max = 5) String displayName) {}

    @PostMapping("/test/things")
    Thing create(@Valid @RequestBody Thing thing) {
      return thing;
    }

    @PostMapping("/test/headers")
    String header(
        @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Other") String other) {
      return key + other;
    }

    @GetMapping("/test/things/{id}")
    String get(@PathVariable UUID id) {
      return id.toString();
    }

    @GetMapping("/test/crash")
    String crash() {
      throw new IllegalStateException("database password is hunter2");
    }

    @GetMapping("/test/busy")
    String busy() {
      throw ApiException.unavailable("keycloak_unavailable", "try again", 5);
    }
  }

  @TestConfiguration
  static class Fixture {
    @Bean
    Things things() {
      return new Things();
    }
  }

  @Autowired MockMvc mvc;

  private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
    return request.with(
        authentication(TestCallers.tenantUser(UUID.randomUUID(), UUID.randomUUID())));
  }

  private MockHttpServletRequestBuilder thing(String json) {
    return signedIn(post("/test/things")).contentType("application/json").content(json);
  }

  @Test
  void accepts_a_valid_body() throws Exception {
    mvc.perform(thing("{\"display_name\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.display_name").value("ok"));
  }

  @Test
  void malformed_json_is_400_without_echoing_input() throws Exception {
    String body =
        mvc.perform(thing("{\"display_name\": secret-value"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("malformed_body"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("secret-value");
  }

  @Test
  void unknown_fields_are_rejected() throws Exception {
    mvc.perform(thing("{\"display_name\":\"ok\",\"tenant_id\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("malformed_body"));
  }

  @Test
  void validation_failures_are_422_naming_the_snake_case_field() throws Exception {
    mvc.perform(thing("{\"display_name\":\"far too long\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.type").value("invalid_request"))
        .andExpect(jsonPath("$.error.code").value("invalid_field"))
        .andExpect(jsonPath("$.error.param").value("display_name"))
        .andExpect(jsonPath("$.error.request_id").isString());
  }

  @Test
  void missing_headers_are_400() throws Exception {
    mvc.perform(signedIn(post("/test/headers")).header("X-Other", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_required"));
    mvc.perform(signedIn(post("/test/headers")).header("Idempotency-Key", "k"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("missing_header"))
        .andExpect(jsonPath("$.error.param").value("X-Other"));
  }

  @Test
  void unknown_routes_and_unparseable_ids_are_404() throws Exception {
    mvc.perform(signedIn(get("/v1/nothing-here")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type").value("not_found"));
    mvc.perform(signedIn(get("/test/things/not-a-uuid"))).andExpect(status().isNotFound());
    mvc.perform(signedIn(get("/test/things/" + UUID.randomUUID()))).andExpect(status().isOk());
  }

  @Test
  void wrong_methods_and_media_types_are_rejected() throws Exception {
    mvc.perform(signedIn(put("/test/things")).contentType("application/json").content("{}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("method_not_allowed"));
    mvc.perform(signedIn(post("/test/things")).contentType("text/plain").content("x"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void unexpected_errors_are_500_and_never_leak_details() throws Exception {
    String body =
        mvc.perform(signedIn(get("/test/crash")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.type").value("internal_error"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("hunter2").doesNotContain("IllegalStateException");
  }

  @Test
  void unavailable_dependencies_tell_the_client_when_to_retry() throws Exception {
    mvc.perform(signedIn(get("/test/busy")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Retry-After", "5"))
        .andExpect(jsonPath("$.error.type").value("unavailable"));
  }

  @Test
  void field_names_map_to_snake_case() {
    assertThat(ApiExceptionHandler.snakeCase("displayName")).isEqualTo("display_name");
    assertThat(ApiExceptionHandler.snakeCase("initialAdminEmail")).isEqualTo("initial_admin_email");
    assertThat(ApiExceptionHandler.snakeCase("name")).isEqualTo("name");
  }
}
