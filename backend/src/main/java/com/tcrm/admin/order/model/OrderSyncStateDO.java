package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** Persisted order_sync_state; external raw payloads and buyer details are never stored. */
@TableName("crm_order_sync_state")
public class OrderSyncStateDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long shopId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant orderCursor;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant refundCursor;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant lastSuccessAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastError;

  /**
   * Latest import kind written by an import processor: ORDER or REFUND; null when never imported.
   */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastImportKind;

  /** Data cutoff time the operator declared for the latest import (task 40). */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant lastImportCutoffAt;

  /** Import task that produced the latest import; {@code crm_task.id}. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long lastImportTaskId;

  /** Last completed settlement detail incremental pull (task 76); null when never pulled. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant settlementPullAt;

  /** Last completed settlement detail daily backfill (task 76); null when never backfilled. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant settlementBackfillAt;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public Instant getOrderCursor() {
    return orderCursor;
  }

  public void setOrderCursor(Instant value) {
    orderCursor = value;
  }

  public Instant getRefundCursor() {
    return refundCursor;
  }

  public void setRefundCursor(Instant value) {
    refundCursor = value;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public void setLastSuccessAt(Instant value) {
    lastSuccessAt = value;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    lastError = value;
  }

  public String getLastImportKind() {
    return lastImportKind;
  }

  public void setLastImportKind(String value) {
    lastImportKind = value;
  }

  public Instant getLastImportCutoffAt() {
    return lastImportCutoffAt;
  }

  public void setLastImportCutoffAt(Instant value) {
    lastImportCutoffAt = value;
  }

  public Long getLastImportTaskId() {
    return lastImportTaskId;
  }

  public void setLastImportTaskId(Long value) {
    lastImportTaskId = value;
  }

  public Instant getSettlementPullAt() {
    return settlementPullAt;
  }

  public void setSettlementPullAt(Instant value) {
    settlementPullAt = value;
  }

  public Instant getSettlementBackfillAt() {
    return settlementBackfillAt;
  }

  public void setSettlementBackfillAt(Instant value) {
    settlementBackfillAt = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
