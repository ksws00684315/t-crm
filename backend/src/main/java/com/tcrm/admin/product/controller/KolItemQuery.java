package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.KolItemFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record KolItemQuery(
    @Size(max = 16) String linkCode,
    @Size(max = 128) String kolKeyword,
    @Min(1) Long shopId,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  KolItemFilterDTO toFilter() {
    return new KolItemFilterDTO(
        blankToNull(linkCode),
        blankToNull(kolKeyword),
        shopId,
        page == null ? 1 : page,
        size == null ? 20 : size);
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }
}
