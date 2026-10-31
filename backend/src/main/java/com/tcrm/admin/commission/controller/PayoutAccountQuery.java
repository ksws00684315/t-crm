package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin list filters (task 15a §4.4, task 82): no status is applied by default, so disabled
 * accounts are listed too; {@code ALL} also means no filter. {@code kolCode} matches exactly and
 * {@code kolId} is the exact KOL id used by the fund dialog.
 */
public record PayoutAccountQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    @Pattern(regexp = "APPROVED|REJECTED|DISABLED|PENDING|ALL") String status,
    @Pattern(regexp = "PAYPAL|USDT") String type,
    @Size(max = 16) String kolCode,
    @Min(1) Long kolId) {
  public PayoutAccountQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }

  PayoutAccountService.Filter toFilter() {
    return new PayoutAccountService.Filter(
        status == null || "ALL".equals(status) ? null : status, type, kolCode, kolId, page, size);
  }
}
