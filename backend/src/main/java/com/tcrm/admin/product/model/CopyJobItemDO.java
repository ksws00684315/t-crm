package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** One source product inside a copy job (task 17b / ADR-019). */
@TableName("crm_copy_job_item")
public class CopyJobItemDO {
  public static final String STATUS_PENDING = "pending";
  public static final String STATUS_SUCCESS = "success";
  public static final String STATUS_FAILED = "failed";

  @TableId private Long id;
  private Long jobId;
  private Long tenantId;
  private String sourceChannel;
  private String sourceProductId;
  private String ruleId;
  private String status;
  private String targetItemId;
  private String errorMessage;
  private Instant updateTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getJobId() {
    return jobId;
  }

  public void setJobId(Long value) {
    this.jobId = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public String getSourceChannel() {
    return sourceChannel;
  }

  public void setSourceChannel(String value) {
    this.sourceChannel = value;
  }

  public String getSourceProductId() {
    return sourceProductId;
  }

  public void setSourceProductId(String value) {
    this.sourceProductId = value;
  }

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String value) {
    this.ruleId = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public String getTargetItemId() {
    return targetItemId;
  }

  public void setTargetItemId(String value) {
    this.targetItemId = value;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String value) {
    this.errorMessage = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    this.updateTime = value;
  }
}
