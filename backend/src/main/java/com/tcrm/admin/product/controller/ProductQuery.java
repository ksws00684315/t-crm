package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ProductFilterDTO;
import com.tcrm.admin.product.service.ProductRules;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ProductQuery(
    @Size(max = 64) String keyword,
    @Min(1) Long shopId,
    @Min(0) @Max(1) Integer status,
    @Size(max = ProductRules.MAX_CATEGORY) String category,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  ProductFilterDTO toFilter() {
    return new ProductFilterDTO(
        keyword, shopId, status, category, page == null ? 1 : page, size == null ? 20 : size);
  }
}
