package com.tcrm.admin.order.controller;

import com.tcrm.admin.order.service.OrderFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Payment interval is inclusive from, exclusive to; IDs never select another company. Task 40 adds
 * the exact Weidian order number and the sales channel (row 09.04). Task 70 (1024 handoff B01-B04)
 * adds the platform order-time range, the creator code/name keyword, the refund-state filter and
 * the abnormal union; kolCode stays for compatibility.
 */
public record OrderQuery(
    @Positive Long shopId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant payFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant payTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant orderTimeFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant orderTimeTo,
    @Size(max = 16) String kolCode,
    @Size(max = 128) String kolKeyword,
    @Pattern(regexp = "UNPAID|PAID|SHIPPED|RECEIVED|COMPLETED|CLOSED|UNKNOWN") String orderStatus,
    @Pattern(regexp = "PENDING|ATTRIBUTED|NON_KOL|CONFLICT|UNMATCHED") String attributionStatus,
    @Pattern(regexp = "REFUNDING|REFUNDED") String refundState,
    Boolean abnormal,
    @Size(max = 32) String orderNo,
    @Size(max = 16) String channel,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {
  public OrderQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
    kolCode = normalize(kolCode);
    kolKeyword = normalize(kolKeyword);
    orderNo = normalize(orderNo);
    channel = normalize(channel);
  }

  @AssertTrue
  public boolean isValidPeriod() {
    return payFrom == null || payTo == null || payFrom.isBefore(payTo);
  }

  /** The order-time window follows the payment window: inclusive from, exclusive to. */
  @AssertTrue
  public boolean isValidOrderTimePeriod() {
    return orderTimeFrom == null || orderTimeTo == null || orderTimeFrom.isBefore(orderTimeTo);
  }

  OrderFilterDTO filter() {
    return new OrderFilterDTO(
        shopId,
        payFrom,
        payTo,
        orderTimeFrom,
        orderTimeTo,
        kolCode,
        kolKeyword,
        orderStatus,
        attributionStatus,
        refundState,
        Boolean.TRUE.equals(abnormal),
        orderNo,
        channel,
        page,
        size);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    var stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
