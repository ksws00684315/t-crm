package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/**
 * Append-only order sync or import failure (task 40 §3.5). Errors are safe English summaries; the
 * import task's own error file carries the per-row detail.
 */
@TableName("crm_order_sync_failure")
public class OrderSyncFailureDO extends BaseEntity {
  /** Shop the failure belongs to; null for an import failure that spans shops. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long shopId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String kind;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String error;

  /** Import task reference ({@code crm_task.id}); null for API sync failures. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long taskId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String value) {
    kind = value;
  }

  public String getError() {
    return error;
  }

  public void setError(String value) {
    error = value;
  }

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long value) {
    taskId = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
