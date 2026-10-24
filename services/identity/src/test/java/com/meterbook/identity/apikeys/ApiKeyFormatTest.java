package com.meterbook.identity.apikeys;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The key format of ADR-0010, checked without a database. */
class ApiKeyFormatTest {

  private static String key(String kind, String random) {
    String body = "mb_" + kind + "_" + random;
    return body + ApiKeys.checksum(body);
  }

  @Test
  void accepts_live_and_test_keys_with_a_valid_checksum() {
    assertThat(ApiKeys.wellFormed(key("live", "a".repeat(40)))).isTrue();
    assertThat(ApiKeys.wellFormed(key("test", "Z9".repeat(20)))).isTrue();
  }

  @Test
  void rejects_other_prefixes_lengths_alphabets_and_checksums() {
    String good = key("live", "a".repeat(40));

    assertThat(ApiKeys.wellFormed(null)).isFalse();
    assertThat(ApiKeys.wellFormed(key("prod", "a".repeat(40)))).isFalse();
    assertThat(ApiKeys.wellFormed(key("live", "a".repeat(39)))).isFalse();
    assertThat(ApiKeys.wellFormed(key("live", "a".repeat(39) + "-"))).isFalse();
    assertThat(ApiKeys.wellFormed(good.substring(0, good.length() - 1) + "0")).isFalse();
    assertThat(ApiKeys.wellFormed("mb_live_" + "a".repeat(40) + "000000")).isFalse();
  }

  @Test
  void the_checksum_is_six_base62_characters_and_deterministic() {
    assertThat(ApiKeys.checksum("mb_live_x")).matches("[0-9A-Za-z]{6}");
    assertThat(ApiKeys.checksum("mb_live_x")).isEqualTo(ApiKeys.checksum("mb_live_x"));
    assertThat(ApiKeys.checksum("")).isEqualTo("000000");
  }
}
