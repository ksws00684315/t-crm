package com.tcrm.admin.task.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Append-only task file; no modification fields and no logical delete. */
@TableName("crm_task_file")
public class TaskFileDO {
  @TableId private Long id;
  private Long taskId;
  private TaskFileKind kind;
  private String fileName;
  private String contentType;
  private byte[] content;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long value) {
    this.taskId = value;
  }

  public TaskFileKind getKind() {
    return kind;
  }

  public void setKind(TaskFileKind value) {
    this.kind = value;
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

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
