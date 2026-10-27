package com.tcrm.admin.order.service;

import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import com.tcrm.admin.order.model.OrderSyncFailureKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Appends rows to {@code crm_order_sync_failure} (task 40 §3.5). Called from sync and import
 * workers inside the owning company's scope; a logging failure never breaks the caller.
 */
@Service
public class OrderSyncFailureWriter {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSyncFailureWriter.class);

  private final OrderSyncFailureMapper failures;

  public OrderSyncFailureWriter(OrderSyncFailureMapper failures) {
    this.failures = failures;
  }

  /**
   * Records one failure.
   *
   * @param shopId shop of the failure; null when an import failure spans shops
   * @param kind failure kind
   * @param error safe English summary (no tokens, no personal data), at most 500 chars
   * @param taskId import task reference; null for API sync failures
   */
  public void record(Long shopId, OrderSyncFailureKind kind, String error, Long taskId) {
    try {
      var row = new OrderSyncFailureDO();
      row.setShopId(shopId);
      row.setKind(kind.name());
      row.setError(
          error == null || error.isBlank()
              ? "unknown error"
              : error.substring(0, Math.min(error.length(), 500)));
      row.setTaskId(taskId);
      failures.insert(row);
    } catch (RuntimeException e) {
      LOG.error("Failed to record order sync failure", e);
    }
  }
}
