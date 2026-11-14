package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/** An order whose detail failed during sync and is retried on later runs (task 145, audit B-5). */
@TableName("crm_order_sync_retry")
public class OrderSyncRetryDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long shopId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String orderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer failCount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastError;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String value) {
    orderId = value;
  }

  public Integer getFailCount() {
    return failCount;
  }

  public void setFailCount(Integer value) {
    failCount = value;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    lastError = value;
  }
}
