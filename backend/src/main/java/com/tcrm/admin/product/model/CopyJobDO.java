package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** External product-link copy job (task 17b / ADR-019). No soft delete. */
@TableName("crm_copy_job")
public class CopyJobDO {
  public static final String STATUS_PROCESSING = "processing";
  public static final String STATUS_COMPLETED = "completed";
  public static final String STATUS_FAILED = "failed";
  public static final String STATUS_PARTIAL_FAILED = "partial_failed";

  @TableId private Long id;
  private Long tenantId;
  private Long kolId;
  private Long tokenId;
  private String jobCode;
  private String callerPlatform;
  private Long targetShopId;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer failCount;
  private Instant createTime;
  private Instant updateTime;

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

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public Long getTokenId() {
    return tokenId;
  }

  public void setTokenId(Long value) {
    this.tokenId = value;
  }

  public String getJobCode() {
    return jobCode;
  }

  public void setJobCode(String value) {
    this.jobCode = value;
  }

  public String getCallerPlatform() {
    return callerPlatform;
  }

  public void setCallerPlatform(String value) {
    this.callerPlatform = value;
  }

  public Long getTargetShopId() {
    return targetShopId;
  }

  public void setTargetShopId(Long value) {
    this.targetShopId = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public Integer getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(Integer value) {
    this.totalCount = value;
  }

  public Integer getSuccessCount() {
    return successCount;
  }

  public void setSuccessCount(Integer value) {
    this.successCount = value;
  }

  public Integer getFailCount() {
    return failCount;
  }

  public void setFailCount(Integer value) {
    this.failCount = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    this.updateTime = value;
  }
}
