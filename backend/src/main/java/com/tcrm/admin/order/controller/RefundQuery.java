package com.tcrm.admin.order.controller;

import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Refund list filters (task 40 §3.3, row 09.04); channel is WEIDIAN today. */
public record RefundQuery(
    @Size(max = 32) String refundNo,
    @Size(max = 32) String orderNo,
    @Size(max = 16) String channel,
    @Pattern(regexp = "PROCESSING|SUCCESS|CLOSED|UNKNOWN") String refundStatus,
    @Pattern(regexp = "REFUND_ONLY|RETURN_REFUND|UNKNOWN") String refundType,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  public RefundQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
    refundNo = normalize(refundNo);
    orderNo = normalize(orderNo);
    channel = normalize(channel);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    var stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
