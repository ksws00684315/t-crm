package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin list filters (task 15a §4.4): status defaults to the PENDING review queue, {@code ALL}
 * shows every status; {@code kolCode} matches exactly.
 */
public record PayoutAccountQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    @Pattern(regexp = "PENDING|APPROVED|REJECTED|DISABLED|ALL") String status,
    @Pattern(regexp = "PAYPAL|USDT") String type,
    @Size(max = 16) String kolCode) {
  public PayoutAccountQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
    status = status == null ? "PENDING" : status;
  }

  PayoutAccountService.Filter toFilter() {
    return new PayoutAccountService.Filter(
        "ALL".equals(status) ? null : status, type, kolCode, page, size);
  }
}
