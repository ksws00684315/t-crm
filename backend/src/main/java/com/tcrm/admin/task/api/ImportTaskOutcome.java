package com.tcrm.admin.task.api;

/** Final outcome of an import task, handed to {@link ImportRowProcessor#onFinish}. */
public record ImportTaskOutcome(int total, int done, int failed, Status status) {
  /** Mirrors {@code TaskStatus} for finish states an import can reach. */
  public enum Status {
    SUCCESS,
    PARTIAL,
    FAILED
  }
}
