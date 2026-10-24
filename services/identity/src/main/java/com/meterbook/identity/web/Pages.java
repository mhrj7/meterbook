package com.meterbook.identity.web;

import java.util.List;
import java.util.function.Function;

/** Cursor pagination (api-standards.md): fetch {@code limit + 1} rows, return {@code limit}. */
public final class Pages {

  /** A page of results and the cursor for the next one. */
  public record Page<T>(List<T> data, boolean hasMore, String nextCursor) {
    public Page {
      data = List.copyOf(data);
    }
  }

  private Pages() {}

  /**
   * Splits one extra fetched row off into {@code hasMore} and the next cursor.
   *
   * @param rows up to {@code limit + 1} rows in cursor order
   * @param cursorOf the cursor value of a row (its sort key)
   */
  public static <T> Page<T> of(List<T> rows, int limit, Function<T, String> cursorOf) {
    boolean hasMore = rows.size() > limit;
    List<T> data = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    return new Page<>(data, hasMore, hasMore ? cursorOf.apply(data.getLast()) : null);
  }
}
