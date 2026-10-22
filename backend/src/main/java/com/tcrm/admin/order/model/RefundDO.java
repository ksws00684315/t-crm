package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/** Persisted refund; external raw payloads and buyer details are never stored. */
@TableName("crm_refund")
public class RefundDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderItemId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String weidianRefundNo;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal amount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String rawStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String refundType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String refundStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant refundTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant finishTime;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long value) {
    orderId = value;
  }

  public Long getOrderItemId() {
    return orderItemId;
  }

  public void setOrderItemId(Long value) {
    orderItemId = value;
  }

  public String getWeidianRefundNo() {
    return weidianRefundNo;
  }

  public void setWeidianRefundNo(String value) {
    weidianRefundNo = value;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal value) {
    amount = value;
  }

  public String getRawStatus() {
    return rawStatus;
  }

  public void setRawStatus(String value) {
    rawStatus = value;
  }

  public String getRefundType() {
    return refundType;
  }

  public void setRefundType(String value) {
    refundType = value;
  }

  public String getRefundStatus() {
    return refundStatus;
  }

  public void setRefundStatus(String value) {
    refundStatus = value;
  }

  public Instant getRefundTime() {
    return refundTime;
  }

  public void setRefundTime(Instant value) {
    refundTime = value;
  }

  public Instant getFinishTime() {
    return finishTime;
  }

  public void setFinishTime(Instant value) {
    finishTime = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
