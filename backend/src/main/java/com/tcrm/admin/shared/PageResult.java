package com.tcrm.admin.shared;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> items, long total, int page, int size) {
  /** Largest accepted page number; bounds OFFSET cost for deep pagination. */
  public static final int MAX_PAGE = 10_000;

  public <U> PageResult<U> map(Function<T, U> mapper) {
    return new PageResult<>(items.stream().map(mapper).toList(), total, page, size);
  }
}
