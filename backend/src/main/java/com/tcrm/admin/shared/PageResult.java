package com.tcrm.admin.shared;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> items, long total, int page, int size) {
  public <U> PageResult<U> map(Function<T, U> mapper) {
    return new PageResult<>(items.stream().map(mapper).toList(), total, page, size);
  }
}
