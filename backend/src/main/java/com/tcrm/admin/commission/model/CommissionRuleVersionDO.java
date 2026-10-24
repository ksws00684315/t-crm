package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only company settlement config version (task 62 / ADR-032 §4, requirement 11.03). */
@TableName("crm_commission_rule_version")
public class CommissionRuleVersionDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Integer versionNo;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String currency;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private BigDecimal minimumWithdrawalCny;

  @TableField(insertStrategy = FieldStrategy.ALWAYS, typeHandler = JsonbStringTypeHandler.class)
  private String channelRules;

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Long actorId;

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

  public Integer getVersionNo() {
    return versionNo;
  }

  public void setVersionNo(Integer value) {
    this.versionNo = value;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    this.currency = value;
  }

  public BigDecimal getMinimumWithdrawalCny() {
    return minimumWithdrawalCny;
  }

  public void setMinimumWithdrawalCny(BigDecimal value) {
    this.minimumWithdrawalCny = value;
  }

  public String getChannelRules() {
    return channelRules;
  }

  public void setChannelRules(String value) {
    this.channelRules = value;
  }

  public Long getActorId() {
    return actorId;
  }

  public void setActorId(Long value) {
    this.actorId = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
