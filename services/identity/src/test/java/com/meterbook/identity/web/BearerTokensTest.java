package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BearerTokensTest {

  private static MockHttpServletRequest with(String authorization) {
    var request = new MockHttpServletRequest();
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    return request;
  }

  @Test
  void extracts_the_credential_of_a_bearer_header_in_any_case() {
    assertThat(BearerTokens.of(with("Bearer abc"))).contains("abc");
    assertThat(BearerTokens.of(with("bearer  abc "))).contains("abc");
  }

  @Test
  void anything_else_carries_no_credential() {
    assertThat(BearerTokens.of(with(null))).isEmpty();
    assertThat(BearerTokens.of(with("Basic dXNlcjpwYXNz"))).isEmpty();
    assertThat(BearerTokens.of(with("Bearer "))).isEmpty();
    assertThat(BearerTokens.of(with("Bearer"))).isEmpty();
  }
}
