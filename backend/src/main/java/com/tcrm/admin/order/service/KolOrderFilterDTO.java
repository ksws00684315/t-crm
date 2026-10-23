package com.tcrm.admin.order.service;

import java.time.Instant;

/** KOL order filters; payFrom is inclusive and payTo exclusive, both UTC. */
public record KolOrderFilterDTO(
    Instant payFrom,
    Instant payTo,
    String orderStatus,
    String orderNo,
    String productName,
    int page,
    int size) {
  /** Existing callers that only filter by source order status remain source-compatible. */
  public KolOrderFilterDTO(Instant payFrom, Instant payTo, String orderStatus, int page, int size) {
    this(payFrom, payTo, orderStatus, null, null, page, size);
  }
}
