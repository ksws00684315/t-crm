package com.tcrm.admin.task.api;

import java.util.List;
import java.util.function.Supplier;

/** Entry point for business modules that run Excel imports as batch tasks. */
public interface ImportTaskApi {
  /** MIME type of every workbook produced or accepted by the framework. */
  String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /**
   * Validates the file synchronously (extension, MIME type, size, ZIP signature, header, row
   * count), stores it, creates a WAITING task owned by the current administrator and queues it. The
   * task remembers the request language, and its error file is rendered in that language.
   *
   * @param type task type
   * @param headerKeys template header message keys ("import.*") in column order; the file is
   *     accepted when its header row equals either the full Chinese or the full English rendering
   * @param file uploaded file
   * @param processor creates the row processor when the task starts; called on the worker thread,
   *     so it must capture everything it needs from the request (such as the submitter)
   * @param maxRows maximum data rows for this import; see {@link TaskType#maxRows()}
   * @return the new task ID
   * @throws com.tcrm.admin.shared.BusinessException TASK_FILE_* (400) when the file is rejected,
   *     TASK_QUEUE_FULL (429) when the queue is full
   */
  Long submitImport(
      TaskType type,
      List<String> headerKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows);

  /**
   * Returns an .xlsx workbook containing only the header row, rendered in the request language.
   *
   * @param headerKeys template header message keys ("import.*") in column order
   */
  byte[] template(List<String> headerKeys);
}
