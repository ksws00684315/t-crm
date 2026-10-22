package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** A scheduled resignation or disable of one BD account, optionally handing its creators over. */
@TableName("sys_user_departure_plan")
public class SysUserDeparturePlanDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long userId;
  private DeparturePlanType planType;
  private Instant effectiveAt;
  private Long heirUserId;
  private DepartureInitiator initiator;
  private String reason;
  private DeparturePlanStatus status;
  private Instant executedAt;
  private DepartureResult result;
  private String resultMessage;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public DeparturePlanType getPlanType() {
    return planType;
  }

  public void setPlanType(DeparturePlanType value) {
    this.planType = value;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public void setEffectiveAt(Instant value) {
    this.effectiveAt = value;
  }

  public Long getHeirUserId() {
    return heirUserId;
  }

  public void setHeirUserId(Long value) {
    this.heirUserId = value;
  }

  public DepartureInitiator getInitiator() {
    return initiator;
  }

  public void setInitiator(DepartureInitiator value) {
    this.initiator = value;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String value) {
    this.reason = value;
  }

  public DeparturePlanStatus getStatus() {
    return status;
  }

  public void setStatus(DeparturePlanStatus value) {
    this.status = value;
  }

  public Instant getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(Instant value) {
    this.executedAt = value;
  }

  public DepartureResult getResult() {
    return result;
  }

  public void setResult(DepartureResult value) {
    this.result = value;
  }

  public String getResultMessage() {
    return resultMessage;
  }

  public void setResultMessage(String value) {
    this.resultMessage = value;
  }
}
