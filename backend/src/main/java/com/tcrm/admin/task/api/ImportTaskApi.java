package com.tcrm.admin.task.api;

import java.util.List;
import java.util.function.Supplier;

/** Entry point for business modules that run Excel imports as batch tasks. */
public interface ImportTaskApi {
  /** MIME type of every workbook produced or accepted by the framework. */
  String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /**
   * Validates the file synchronously (extension, MIME type, size, ZIP signature, header, row
   * count), stores it, creates a WAITING task owned by the current administrator and queues it.
   *
   * @param type task type
   * @param headers template header texts in column order
   * @param file uploaded file
   * @param processor creates the row processor when the task starts; called on the worker thread,
   *     so it must capture everything it needs from the request (such as the submitter)
   * @return the new task ID
   * @throws com.tcrm.admin.shared.BusinessException TASK_FILE_* (400) when the file is rejected,
   *     TASK_QUEUE_FULL (429) when the queue is full
   */
  Long submitImport(
      TaskType type,
      List<String> headers,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor);

  /**
   * Returns an .xlsx workbook containing only the header row.
   *
   * @param headers template header texts in column order
   */
  byte[] template(List<String> headers);
}
