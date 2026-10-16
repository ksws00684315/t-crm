package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/**
 * Agent platform link template (task 11 §4.2; design §5.2, ADR-019). Templates are disabled, never
 * deleted; the code is immutable after creation.
 */
@TableName("crm_link_template")
public class LinkTemplateDO extends BaseEntity {
  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private String templateCode;

  private String templateName;

  private String templateUrl;

  private String channelCode;

  private Integer sortOrder;

  private Integer status;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public String getTemplateCode() {
    return templateCode;
  }

  public void setTemplateCode(String value) {
    this.templateCode = value;
  }

  public String getTemplateName() {
    return templateName;
  }

  public void setTemplateName(String value) {
    this.templateName = value;
  }

  public String getTemplateUrl() {
    return templateUrl;
  }

  public void setTemplateUrl(String value) {
    this.templateUrl = value;
  }

  public String getChannelCode() {
    return channelCode;
  }

  public void setChannelCode(String value) {
    this.channelCode = value;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer value) {
    this.sortOrder = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }
}
