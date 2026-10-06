package com.tcrm.admin.link.controller;

import com.tcrm.admin.link.service.LinkFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Link list filters: {@code kolCode} is required (400 when missing). */
public record LinkQuery(
    @NotBlank @Size(max = 16) String kolCode,
    @Size(max = 32) String productCode,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  public LinkQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }

  LinkFilterDTO toFilter() {
    return new LinkFilterDTO(kolCode.trim(), productCode, page, size);
  }
}
