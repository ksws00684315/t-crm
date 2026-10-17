package com.tcrm.admin.notification.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Append-only in-app message. Text is not stored; {@code params} is rendered by the admin UI. */
@TableName(value = "crm_notification", autoResultMap = true)
public class NotificationDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long userId;
  private NotificationType type;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String params;

  private String bizType;
  private Long bizId;
  private Instant readTime;
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

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public NotificationType getType() {
    return type;
  }

  public void setType(NotificationType value) {
    this.type = value;
  }

  public String getParams() {
    return params;
  }

  public void setParams(String value) {
    this.params = value;
  }

  public String getBizType() {
    return bizType;
  }

  public void setBizType(String value) {
    this.bizType = value;
  }

  public Long getBizId() {
    return bizId;
  }

  public void setBizId(Long value) {
    this.bizId = value;
  }

  public Instant getReadTime() {
    return readTime;
  }

  public void setReadTime(Instant value) {
    this.readTime = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
