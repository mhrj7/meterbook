package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestIdsTest {

  private static Clock at(long millis) {
    return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
  }

  @Test
  void generates_prefixed_26_character_crockford_ids() {
    String id = new RequestIds(Clock.systemUTC(), new SecureRandom()).next();

    assertThat(id).matches("req_[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(RequestIds.isValid(id)).isTrue();
  }

  @Test
  void encodes_the_timestamp_so_ids_sort_by_time() {
    SecureRandom random = new SecureRandom();
    String earlier = new RequestIds(at(1_700_000_000_000L), random).next();
    String later = new RequestIds(at(1_700_000_000_001L), random).next();

    assertThat(earlier.substring(0, 14)).isNotEqualTo(later.substring(0, 14));
    assertThat(earlier.compareTo(later)).isNegative();
  }

  @Test
  void encodes_the_epoch_and_the_maximum_timestamp_at_the_boundaries() {
    SecureRandom random = new SecureRandom();

    assertThat(new RequestIds(at(0), random).next()).startsWith("req_0000000000");
    assertThat(new RequestIds(at((1L << 48) - 1), random).next()).startsWith("req_7ZZZZZZZZZ");
  }

  @Test
  void ids_are_unique() {
    RequestIds ids = new RequestIds(at(1_700_000_000_000L), new SecureRandom());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      seen.add(ids.next());
    }
    assertThat(seen).hasSize(10_000);
  }

  @Test
  void rejects_ids_not_in_our_format() {
    assertThat(RequestIds.isValid(null)).isFalse();
    assertThat(RequestIds.isValid("")).isFalse();
    assertThat(RequestIds.isValid("req_123")).isFalse();
    assertThat(RequestIds.isValid("abc_01JBZ8K6S9M2Q4T7V0X3Y5A8C1")).isFalse();
    assertThat(RequestIds.isValid("req_01JBZ8K6S9M2Q4T7V0X3Y5A8CU"))
        .isFalse(); // U is not Crockford
    assertThat(RequestIds.isValid("req_01jbz8k6s9m2q4t7v0x3y5a8c1")).isFalse();
    assertThat(RequestIds.isValid("req_01JBZ8K6S9M2Q4T7V0X3Y5A8C1\n")).isFalse();
  }
}
