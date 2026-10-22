package com.tcrm.admin.order.model;

/** Kind of an {@code crm_order_sync_failure} row (task 40 §3.5). */
public enum OrderSyncFailureKind {
  ORDER_SYNC,
  REFUND_SYNC,
  ORDER_IMPORT,
  REFUND_IMPORT
}
