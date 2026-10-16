package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * Business file row (crm_file, task 15a). Append-only except the one-time {@code bizId} backfill
 * when the owning business record is saved; no modification fields and no logical delete.
 */
@TableName("crm_file")
public class BizFileDO {
  @TableId private Long id;

  /** Owning company. Never written by updates. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private FileBizType bizType;

  /** Backfilled once, when the business record that owns the file is saved. */
  private Long bizId;

  private String fileName;

  private String contentType;

  private Long sizeBytes;

  private byte[] content;

  /** Uploader id: a crm_kol.id or sys_user.id, disambiguated by {@code createByType}. */
  private Long createBy;

  /** ADMIN or KOL. */
  private String createByType;

  @TableField(fill = FieldFill.INSERT)
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

  public FileBizType getBizType() {
    return bizType;
  }

  public void setBizType(FileBizType value) {
    this.bizType = value;
  }

  public Long getBizId() {
    return bizId;
  }

  public void setBizId(Long value) {
    this.bizId = value;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String value) {
    this.fileName = value;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String value) {
    this.contentType = value;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long value) {
    this.sizeBytes = value;
  }

  public byte[] getContent() {
    return content;
  }

  public void setContent(byte[] value) {
    this.content = value;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long value) {
    this.createBy = value;
  }

  public String getCreateByType() {
    return createByType;
  }

  public void setCreateByType(String value) {
    this.createByType = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
