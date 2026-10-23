package com.meterbook.identity.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.MutableClock;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.web.ApiException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, IdempotencyTest.Fixture.class})
class IdempotencyTest {

  /** A mutating endpoint that counts how often it really runs. */
  @RestController
  static class Widgets {
    @Autowired Idempotency idempotency;
    final AtomicInteger runs = new AtomicInteger();
    volatile CountDownLatch hold = new CountDownLatch(0);
    volatile boolean fail;

    record Widget(String name) {}

    @PostMapping({"/test/widgets", "/test/gadgets"})
    ResponseEntity<?> create(
        CallerAuthentication caller,
        @RequestHeader(name = Idempotency.HEADER, required = false) String key,
        @RequestBody Widget widget,
        jakarta.servlet.http.HttpServletRequest request) {
      return idempotency.execute(
          caller.getName(),
          key,
          "POST " + request.getRequestURI(),
          widget,
          () -> {
            int n = runs.incrementAndGet();
            await(hold);
            if (fail) {
              throw ApiException.invalid("widget_rejected", "no", "name");
            }
            return ResponseEntity.created(URI.create("/test/widgets/" + n))
                .body(new Widget(widget.name() + "#" + n));
          });
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @TestConfiguration
  static class Fixture {
    @Bean
    Widgets widgets() {
      return new Widgets();
    }

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired Widgets widgets;
  @Autowired MutableClock clock;
  @Autowired IdempotencyStore store;
  @Autowired Clock injectedClock;

  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void reset() {
    widgets.runs.set(0);
    widgets.fail = false;
    widgets.hold = new CountDownLatch(0);
  }

  private MockHttpServletRequestBuilder create(String path, UUID caller, String key, String name) {
    MockHttpServletRequestBuilder request =
        post(path)
            .with(authentication(TestCallers.tenantUser(caller, UUID.randomUUID())))
            .contentType("application/json")
            .content("{\"name\":\"" + name + "\"}");
    return key == null ? request : request.header(Idempotency.HEADER, key);
  }

  @Test
  void the_application_runs_on_the_test_clock() {
    assertThat(injectedClock).isSameAs(clock);
  }

  @Test
  void requires_a_key() throws Exception {
    mvc.perform(create("/test/widgets", user, null, "a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_required"))
        .andExpect(jsonPath("$.error.param").value("Idempotency-Key"));
    mvc.perform(create("/test/widgets", user, " ", "a"))
        .andExpect(jsonPath("$.error.code").value("idempotency_key_required"));
    assertThat(widgets.runs).hasValue(0);
  }

  @Test
  void rejects_keys_longer_than_255_characters() throws Exception {
    mvc.perform(create("/test/widgets", user, "k".repeat(256), "a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_too_long"));
    mvc.perform(create("/test/widgets", user, "k".repeat(255), "a"))
        .andExpect(status().isCreated());
  }

  @Test
  void replays_the_stored_response_for_the_same_key_and_body() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/test/widgets/1"))
        .andExpect(header().doesNotExist(Idempotency.REPLAYED_HEADER))
        .andExpect(jsonPath("$.name").value("a#1"));

    mvc.perform(create("/test/widgets", user, key, "a"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/test/widgets/1"))
        .andExpect(header().string(Idempotency.REPLAYED_HEADER, "true"))
        .andExpect(
            content()
                .json("{\"name\":\"a#1\"}", org.springframework.test.json.JsonCompareMode.STRICT));

    assertThat(widgets.runs).hasValue(1);
  }

  @Test
  void rejects_the_same_key_with_a_different_body() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(status().isCreated());

    mvc.perform(create("/test/widgets", user, key, "b"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    assertThat(widgets.runs).hasValue(1);
  }

  @Test
  void rejects_the_same_key_on_another_endpoint() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(status().isCreated());

    mvc.perform(create("/test/gadgets", user, key, "a"))
        .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
  }

  @Test
  void keys_belong_to_one_caller() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(jsonPath("$.name").value("a#1"));
    mvc.perform(create("/test/widgets", UUID.randomUUID(), key, "a"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("a#2"));
  }

  @Test
  void a_failed_attempt_releases_the_key() throws Exception {
    String key = UUID.randomUUID().toString();
    widgets.fail = true;
    mvc.perform(create("/test/widgets", user, key, "a"))
        .andExpect(status().isUnprocessableContent());

    widgets.fail = false;
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(status().isCreated());
    assertThat(widgets.runs).hasValue(2);
  }

  @Test
  void a_key_can_be_reused_after_24_hours() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(jsonPath("$.name").value("a#1"));

    clock.advance(IdempotencyStore.RETENTION.plus(Duration.ofSeconds(1)));
    mvc.perform(create("/test/widgets", user, key, "b"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("b#2"));
  }

  @Test
  void expired_keys_are_purged() throws Exception {
    String key = UUID.randomUUID().toString();
    mvc.perform(create("/test/widgets", user, key, "a")).andExpect(status().isCreated());

    assertThat(store.hashOf(user.toString(), key)).isPresent();
    clock.advance(IdempotencyStore.RETENTION.plus(Duration.ofMinutes(1)));
    assertThat(store.purgeExpired()).isPositive();
    assertThat(store.hashOf(user.toString(), key)).isEmpty();
  }

  @Test
  void concurrent_requests_with_one_key_run_once() throws Exception {
    String key = UUID.randomUUID().toString();
    widgets.hold = new CountDownLatch(1);
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  var response =
                      mvc.perform(create("/test/widgets", user, key, "a"))
                          .andReturn()
                          .getResponse();
                  return response.getStatus() + " " + response.getHeader("Retry-After");
                }));
      }
      // Everyone but the owner of the key returns at once with 409; then the owner may finish.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (results.stream().filter(Future::isDone).count() < callers - 1
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      widgets.hold.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> result : results) {
        outcomes.add(result.get(10, TimeUnit.SECONDS));
      }
      assertThat(outcomes)
          .containsOnlyOnce("201 null")
          .filteredOn(o -> !o.startsWith("201"))
          .containsOnly("409 1");
      assertThat(widgets.runs).hasValue(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void a_different_body_is_rejected_even_while_the_first_request_runs() throws Exception {
    String key = UUID.randomUUID().toString();
    assertThat(store.claim(user.toString(), key, "0".repeat(64)))
        .isInstanceOf(IdempotencyStore.Claim.Started.class);

    mvc.perform(create("/test/widgets", user, key, "a"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    assertThat(widgets.runs).hasValue(0);
  }

  @Test
  void release_never_drops_a_completed_response() {
    String key = UUID.randomUUID().toString();
    store.claim(user.toString(), key, "0".repeat(64));
    store.complete(user.toString(), key, new IdempotencyStore.StoredResponse(201, "{}", null));

    store.release(user.toString(), key);

    assertThat(store.claim(user.toString(), key, "0".repeat(64)))
        .isEqualTo(
            new IdempotencyStore.Claim.Replay(
                new IdempotencyStore.StoredResponse(201, "{}", null)));
  }
}
