package com.tcrm.admin.task.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import com.tcrm.admin.task.api.TaskType;
import java.time.Instant;

@TableName(value = "crm_task", autoResultMap = true)
public class TaskDO extends BaseEntity {
  private TaskType taskType;
  private TaskStatus status;
  private Integer total;
  private Integer done;
  private Integer failed;
  private String message;

  /** Request language at submission ("zh-CN" / "en-US"); the error file is rendered with it. */
  private String locale;

  private Instant startedAt;
  private Instant finishedAt;

  /** Submitter parameters as a JSON object (task 40: import dataCutoffTime); null when none. */
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String params;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public TaskType getTaskType() {
    return taskType;
  }

  public void setTaskType(TaskType value) {
    this.taskType = value;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus value) {
    this.status = value;
  }

  public Integer getTotal() {
    return total;
  }

  public void setTotal(Integer value) {
    this.total = value;
  }

  public Integer getDone() {
    return done;
  }

  public void setDone(Integer value) {
    this.done = value;
  }

  public Integer getFailed() {
    return failed;
  }

  public void setFailed(Integer value) {
    this.failed = value;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String value) {
    this.message = value;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String value) {
    this.locale = value;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant value) {
    this.startedAt = value;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant value) {
    finishedAt = value;
  }

  public String getParams() {
    return params;
  }

  public void setParams(String value) {
    params = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
