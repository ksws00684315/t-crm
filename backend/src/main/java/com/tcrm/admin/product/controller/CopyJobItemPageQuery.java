package com.tcrm.admin.product.controller;

import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CopyJobItemPageQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page, @Min(1) @Max(100) Integer size) {
  int pageOrDefault() {
    return page == null ? 1 : page;
  }

  int sizeOrDefault() {
    return size == null ? 20 : size;
  }
}
