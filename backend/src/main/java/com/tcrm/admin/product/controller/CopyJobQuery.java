package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.CopyJobQueryDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CopyJobQuery(
    @Size(max = 64) String jobCode,
    @Size(max = 16) String targetChannel,
    @Size(max = 32) String status,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  CopyJobQueryDTO toFilter() {
    return new CopyJobQueryDTO(
        jobCode, targetChannel, status, page == null ? 1 : page, size == null ? 20 : size);
  }
}
