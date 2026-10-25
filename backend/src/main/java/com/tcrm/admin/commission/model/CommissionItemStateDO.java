package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

/** Per order-item commission source snapshot (task 62 / ADR-032 §2). */
@TableName("crm_commission_item_state")
public class CommissionItemStateDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderItemId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long kolId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String sourceKey;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String sourceVersion;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant sourceUpdatedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal basisCny;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal successfulRefundCny;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Boolean basisIncludesRefund;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String settlementState;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant settlementTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant completedTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Boolean afterSales;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Boolean verified;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer configVersion;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long ruleVersionId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal accruedCommissionCny;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal releasedCommissionCny;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String releaseBasis;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant releaseAt;

  @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
  private Instant createTime;

  @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
  private Long updateBy;

  @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
  private Instant updateTime;

  @TableField("is_deleted")
  private Boolean deleted;

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean value) {
    this.deleted = value;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long value) {
    this.orderId = value;
  }

  public Long getOrderItemId() {
    return orderItemId;
  }

  public void setOrderItemId(Long value) {
    this.orderItemId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public String getSourceKey() {
    return sourceKey;
  }

  public void setSourceKey(String value) {
    this.sourceKey = value;
  }

  public String getSourceVersion() {
    return sourceVersion;
  }

  public void setSourceVersion(String value) {
    this.sourceVersion = value;
  }

  public Instant getSourceUpdatedAt() {
    return sourceUpdatedAt;
  }

  public void setSourceUpdatedAt(Instant value) {
    this.sourceUpdatedAt = value;
  }

  public BigDecimal getBasisCny() {
    return basisCny;
  }

  public void setBasisCny(BigDecimal value) {
    this.basisCny = value;
  }

  public BigDecimal getSuccessfulRefundCny() {
    return successfulRefundCny;
  }

  public void setSuccessfulRefundCny(BigDecimal value) {
    this.successfulRefundCny = value;
  }

  public Boolean getBasisIncludesRefund() {
    return basisIncludesRefund;
  }

  public void setBasisIncludesRefund(Boolean value) {
    this.basisIncludesRefund = value;
  }

  public String getSettlementState() {
    return settlementState;
  }

  public void setSettlementState(String value) {
    this.settlementState = value;
  }

  public Instant getSettlementTime() {
    return settlementTime;
  }

  public void setSettlementTime(Instant value) {
    this.settlementTime = value;
  }

  public Instant getCompletedTime() {
    return completedTime;
  }

  public void setCompletedTime(Instant value) {
    this.completedTime = value;
  }

  public Boolean getAfterSales() {
    return afterSales;
  }

  public void setAfterSales(Boolean value) {
    this.afterSales = value;
  }

  public Boolean getVerified() {
    return verified;
  }

  public void setVerified(Boolean value) {
    this.verified = value;
  }

  public Integer getConfigVersion() {
    return configVersion;
  }

  public void setConfigVersion(Integer value) {
    this.configVersion = value;
  }

  public Long getRuleVersionId() {
    return ruleVersionId;
  }

  public void setRuleVersionId(Long value) {
    this.ruleVersionId = value;
  }

  public BigDecimal getAccruedCommissionCny() {
    return accruedCommissionCny;
  }

  public void setAccruedCommissionCny(BigDecimal value) {
    this.accruedCommissionCny = value;
  }

  public BigDecimal getReleasedCommissionCny() {
    return releasedCommissionCny;
  }

  public void setReleasedCommissionCny(BigDecimal value) {
    this.releasedCommissionCny = value;
  }

  public String getReleaseBasis() {
    return releaseBasis;
  }

  public void setReleaseBasis(String value) {
    this.releaseBasis = value;
  }

  public Instant getReleaseAt() {
    return releaseAt;
  }

  public void setReleaseAt(Instant value) {
    this.releaseAt = value;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long value) {
    this.createBy = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long value) {
    this.updateBy = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    this.updateTime = value;
  }
}
