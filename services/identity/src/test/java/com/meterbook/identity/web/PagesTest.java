package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagesTest {

  @Test
  void a_short_result_is_the_last_page() {
    Pages.Page<String> page = Pages.of(List.of("a", "b"), 2, s -> s);

    assertThat(page.data()).containsExactly("a", "b");
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void one_extra_row_means_another_page_starting_after_the_last_returned() {
    Pages.Page<String> page = Pages.of(List.of("a", "b", "c"), 2, s -> "cursor-" + s);

    assertThat(page.data()).containsExactly("a", "b");
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isEqualTo("cursor-b");
  }

  @Test
  void an_empty_result_is_an_empty_last_page() {
    assertThat(Pages.of(List.<String>of(), 50, s -> s))
        .isEqualTo(new Pages.Page<>(List.of(), false, null));
  }
}
