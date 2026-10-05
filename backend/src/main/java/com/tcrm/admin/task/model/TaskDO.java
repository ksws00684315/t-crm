package com.tcrm.admin.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import com.tcrm.admin.task.api.TaskType;
import java.time.Instant;

@TableName("crm_task")
public class TaskDO extends BaseEntity {
  private TaskType taskType;
  private TaskStatus status;
  private Integer total;
  private Integer done;
  private Integer failed;
  private String message;
  private Instant startedAt;
  private Instant finishedAt;

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
    this.finishedAt = value;
  }
}
