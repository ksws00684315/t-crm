package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only commission item revision (task 62 / ADR-032 §2). */
@TableName("crm_commission_item_revision")
public class CommissionItemRevisionDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Long itemStateId;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String sourceVersion;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal basisCny;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal successfulRefundCny;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal rate;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Integer configVersion;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Long ruleVersionId;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal accruedCny;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal deltaCny;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String releaseBasis;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String evidence;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Instant createTime;

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

  public Long getItemStateId() {
    return itemStateId;
  }

  public void setItemStateId(Long value) {
    this.itemStateId = value;
  }

  public String getSourceVersion() {
    return sourceVersion;
  }

  public void setSourceVersion(String value) {
    this.sourceVersion = value;
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

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal value) {
    this.rate = value;
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

  public BigDecimal getAccruedCny() {
    return accruedCny;
  }

  public void setAccruedCny(BigDecimal value) {
    this.accruedCny = value;
  }

  public BigDecimal getDeltaCny() {
    return deltaCny;
  }

  public void setDeltaCny(BigDecimal value) {
    this.deltaCny = value;
  }

  public String getReleaseBasis() {
    return releaseBasis;
  }

  public void setReleaseBasis(String value) {
    this.releaseBasis = value;
  }

  public String getEvidence() {
    return evidence;
  }

  public void setEvidence(String value) {
    this.evidence = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
