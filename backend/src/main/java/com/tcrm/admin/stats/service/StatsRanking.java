package com.tcrm.admin.stats.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.Comparator;
import java.util.List;

/** Validated, stable ranking pagination shared by the dashboard queries. */
public record StatsRanking(int page, int size, boolean descending) {
  public static StatsRanking resolve(Integer page, Integer size, String direction) {
    int requestedPage = page == null ? 1 : page;
    int requestedSize = size == null ? 20 : size;
    if (requestedPage < 1
        || requestedPage > PageResult.MAX_PAGE
        || (requestedSize != 20 && requestedSize != 50 && requestedSize != 100)
        || (direction != null
            && !"asc".equalsIgnoreCase(direction)
            && !"desc".equalsIgnoreCase(direction))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return new StatsRanking(requestedPage, requestedSize, !"asc".equalsIgnoreCase(direction));
  }

  /** The direction only changes the primary order. Nulls are last in both directions. */
  <T, V extends Comparable<? super V>> Comparator<T> compare(
      java.util.function.Function<T, V> key) {
    Comparator<V> values = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
    return Comparator.comparing(key, Comparator.nullsLast(values));
  }

  <T> PageResult<T> slice(List<T> rows, Comparator<T> comparator) {
    int offset = (page - 1) * size;
    return new PageResult<>(
        rows.stream().sorted(comparator).skip(offset).limit(size).toList(),
        rows.size(),
        page,
        size);
  }
}
