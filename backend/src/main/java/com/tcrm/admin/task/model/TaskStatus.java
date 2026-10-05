package com.tcrm.admin.task.model;

/** Batch task lifecycle; see crm_task.status. */
public enum TaskStatus {
  WAITING,
  RUNNING,
  SUCCESS,
  PARTIAL,
  FAILED;

  /** Final status of a task that processed every row: no failure, some failures, or all failed. */
  public static TaskStatus finish(int total, int failed) {
    if (failed == 0) {
      return SUCCESS;
    }
    return failed >= total ? FAILED : PARTIAL;
  }
}
