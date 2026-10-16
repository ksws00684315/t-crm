package com.tcrm.admin.order.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** vdian.order.get v2.0 status, not the numeric order-list status; see design §8.1. */
public enum OrderStatus {
  UNPAID,
  PAID,
  SHIPPED,
  RECEIVED,
  COMPLETED,
  CLOSED,
  UNKNOWN;
  private static final Logger LOG = LoggerFactory.getLogger(OrderStatus.class);

  public static OrderStatus from(String raw) {
    if (raw != null) {
      switch (raw) {
        case "unpay", "part_pay":
          return UNPAID;
        case "pay":
          return PAID;
        case "ship", "split_deliver":
          return SHIPPED;
        case "accept":
          return RECEIVED;
        case "finish":
          return COMPLETED;
        case "close":
          return CLOSED;
        default:
          break;
      }
    }
    LOG.warn("Unknown Weidian order detail status; order is not effective");
    return UNKNOWN;
  }
}
