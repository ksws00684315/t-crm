package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** Cleanup detail row for deleting a KOL's exclusive Weidian item (task 29 / ADR-025). */
@TableName("crm_kol_cleanup_item")
public class KolCleanupItemDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;
  private String kolCode;
  private Long bdUserId;
  private Long kolItemId;
  private Long shopId;
  private String weidianItemId;
  private String status;

  @TableField("trigger_type")
  private String triggerType;

  private String reason;
  private String failReason;
  private Instant cleanupTime;
  private Instant finishTime;

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

  public String getKolCode() {
    return kolCode;
  }

  public void setKolCode(String value) {
    this.kolCode = value;
  }

  public Long getBdUserId() {
    return bdUserId;
  }

  public void setBdUserId(Long value) {
    this.bdUserId = value;
  }

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    this.shopId = value;
  }

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public String getTriggerType() {
    return triggerType;
  }

  public void setTriggerType(String value) {
    this.triggerType = value;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String value) {
    this.reason = value;
  }

  public String getFailReason() {
    return failReason;
  }

  public void setFailReason(String value) {
    this.failReason = value;
  }

  public Instant getCleanupTime() {
    return cleanupTime;
  }

  public void setCleanupTime(Instant value) {
    this.cleanupTime = value;
  }

  public Instant getFinishTime() {
    return finishTime;
  }

  public void setFinishTime(Instant value) {
    this.finishTime = value;
  }
}
