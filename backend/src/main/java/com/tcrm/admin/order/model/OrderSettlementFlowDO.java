package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One Weidian settlement flow row of {@code shop.order.trade.detail}, stored verbatim (task 76,
 * ADR-032 2026-10-28 revision). Append-only with fingerprint deduplication: the API has no flow id,
 * so repeated pulls of the same row reuse the existing one.
 */
@TableName("crm_order_settlement_flow")
public class OrderSettlementFlowDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long shopId;

  /** orderId exactly as returned; matched against {@code crm_order.weidian_order_id} verbatim. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String weidianOrderId;

  /** Local order matched at pull time; null when the order is unknown or arrived later. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String refundId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String tradeType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String tradeSubType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant tradeTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String remark;

  /** Raw amount exactly as returned; no unit conversion. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal amountRaw;

  /** Converted CNY amount (2 decimals); null when the raw amount is missing or unparsable. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal amountCny;

  /** {@code INCOME_DETAIL} or {@code PRE_SETTLE_REFUND}. */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String queryType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String fingerprint;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant firstPulledAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant lastPulledAt;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public String getWeidianOrderId() {
    return weidianOrderId;
  }

  public void setWeidianOrderId(String value) {
    weidianOrderId = value;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long value) {
    orderId = value;
  }

  public String getRefundId() {
    return refundId;
  }

  public void setRefundId(String value) {
    refundId = value;
  }

  public String getTradeType() {
    return tradeType;
  }

  public void setTradeType(String value) {
    tradeType = value;
  }

  public String getTradeSubType() {
    return tradeSubType;
  }

  public void setTradeSubType(String value) {
    tradeSubType = value;
  }

  public Instant getTradeTime() {
    return tradeTime;
  }

  public void setTradeTime(Instant value) {
    tradeTime = value;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String value) {
    remark = value;
  }

  public BigDecimal getAmountRaw() {
    return amountRaw;
  }

  public void setAmountRaw(BigDecimal value) {
    amountRaw = value;
  }

  public BigDecimal getAmountCny() {
    return amountCny;
  }

  public void setAmountCny(BigDecimal value) {
    amountCny = value;
  }

  public String getQueryType() {
    return queryType;
  }

  public void setQueryType(String value) {
    queryType = value;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(String value) {
    fingerprint = value;
  }

  public Instant getFirstPulledAt() {
    return firstPulledAt;
  }

  public void setFirstPulledAt(Instant value) {
    firstPulledAt = value;
  }

  public Instant getLastPulledAt() {
    return lastPulledAt;
  }

  public void setLastPulledAt(Instant value) {
    lastPulledAt = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
