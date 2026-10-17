package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** An immediate or scheduled BD ownership transfer. */
@TableName("crm_kol_bd_transfer")
public class KolBdTransferDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;
  private Long fromBdUserId;
  private Long toBdUserId;
  private Instant effectiveAt;
  private BdTransferStatus status;
  private Instant executedAt;
  private String cancelReason;
  private String reason;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public Long getFromBdUserId() {
    return fromBdUserId;
  }

  public void setFromBdUserId(Long value) {
    this.fromBdUserId = value;
  }

  public Long getToBdUserId() {
    return toBdUserId;
  }

  public void setToBdUserId(Long value) {
    this.toBdUserId = value;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public void setEffectiveAt(Instant value) {
    this.effectiveAt = value;
  }

  public BdTransferStatus getStatus() {
    return status;
  }

  public void setStatus(BdTransferStatus value) {
    this.status = value;
  }

  public Instant getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(Instant value) {
    this.executedAt = value;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public void setCancelReason(String value) {
    this.cancelReason = value;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String value) {
    this.reason = value;
  }
}
