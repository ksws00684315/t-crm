package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.KolItemFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record KolItemQuery(
    @Size(max = 16) String kolCode,
    @Size(max = 16) String productCode,
    @Min(1) Long shopId,
    @Size(max = 32) String weidianItemId,
    Boolean current,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  KolItemFilterDTO toFilter() {
    return new KolItemFilterDTO(
        kolCode,
        productCode,
        shopId,
        weidianItemId,
        current == null || current,
        page == null ? 1 : page,
        size == null ? 20 : size);
  }
}
