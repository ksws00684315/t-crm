package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/** Persisted order; external raw payloads and buyer details are never stored. */
@TableName("crm_order")
public class OrderDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long shopId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String weidianOrderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long kolId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long bdUserId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant settledTime;

  /**
   * Settlement basis check result (task 76): null when a basis was computed or nothing was checked
   * yet; otherwise one of the {@code SettlementBasisCalculator.REASON_*} codes.
   */
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String commissionBasisReason;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String attributionStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String orderStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String rawStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String buyerHash;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant orderTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant payTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant receiveTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant closeTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal totalAmount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal payAmount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal freight;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal discount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer source;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long importBatchId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant lastSyncTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant sourceUpdateTime;

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

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    kolId = value;
  }

  public Long getBdUserId() {
    return bdUserId;
  }

  public void setBdUserId(Long value) {
    bdUserId = value;
  }

  public Instant getSettledTime() {
    return settledTime;
  }

  public void setSettledTime(Instant value) {
    settledTime = value;
  }

  public String getCommissionBasisReason() {
    return commissionBasisReason;
  }

  public void setCommissionBasisReason(String value) {
    commissionBasisReason = value;
  }

  public String getAttributionStatus() {
    return attributionStatus;
  }

  public void setAttributionStatus(String value) {
    attributionStatus = value;
  }

  public String getOrderStatus() {
    return orderStatus;
  }

  public void setOrderStatus(String value) {
    orderStatus = value;
  }

  public String getRawStatus() {
    return rawStatus;
  }

  public void setRawStatus(String value) {
    rawStatus = value;
  }

  public String getBuyerHash() {
    return buyerHash;
  }

  public void setBuyerHash(String value) {
    buyerHash = value;
  }

  public Instant getOrderTime() {
    return orderTime;
  }

  public void setOrderTime(Instant value) {
    orderTime = value;
  }

  public Instant getPayTime() {
    return payTime;
  }

  public void setPayTime(Instant value) {
    payTime = value;
  }

  public Instant getReceiveTime() {
    return receiveTime;
  }

  public void setReceiveTime(Instant value) {
    receiveTime = value;
  }

  public Instant getCloseTime() {
    return closeTime;
  }

  public void setCloseTime(Instant value) {
    closeTime = value;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal value) {
    totalAmount = value;
  }

  public BigDecimal getPayAmount() {
    return payAmount;
  }

  public void setPayAmount(BigDecimal value) {
    payAmount = value;
  }

  public BigDecimal getFreight() {
    return freight;
  }

  public void setFreight(BigDecimal value) {
    freight = value;
  }

  public BigDecimal getDiscount() {
    return discount;
  }

  public void setDiscount(BigDecimal value) {
    discount = value;
  }

  public Integer getSource() {
    return source;
  }

  public void setSource(Integer value) {
    source = value;
  }

  public Long getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(Long value) {
    importBatchId = value;
  }

  public Instant getLastSyncTime() {
    return lastSyncTime;
  }

  public void setLastSyncTime(Instant value) {
    lastSyncTime = value;
  }

  public Instant getSourceUpdateTime() {
    return sourceUpdateTime;
  }

  public void setSourceUpdateTime(Instant value) {
    sourceUpdateTime = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
