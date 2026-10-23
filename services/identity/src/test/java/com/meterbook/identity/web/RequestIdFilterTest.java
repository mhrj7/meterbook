package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter =
      new RequestIdFilter(new RequestIds(Clock.systemUTC(), new SecureRandom()));

  @Test
  void puts_the_id_in_the_logging_context_only_while_the_request_runs() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> during = new AtomicReference<>();
    FilterChain chain = (req, res) -> during.set(MDC.get("request_id"));

    filter.doFilter(request, response, chain);

    assertThat(during.get()).isEqualTo(response.getHeader("Request-Id"));
    assertThat(RequestIdFilter.of(request)).isEqualTo(during.get());
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void clears_the_logging_context_even_when_the_request_fails() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
        .isInstanceOf(IllegalStateException.class);
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void a_request_that_never_passed_the_filter_has_an_empty_id() {
    assertThat(RequestIdFilter.of(new MockHttpServletRequest())).isEmpty();
  }
}
