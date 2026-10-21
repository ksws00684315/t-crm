package com.tcrm.admin.task.api;

import java.util.Collection;
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
   * Same as {@link #submitImport(TaskType, List, ImportFileDTO, Supplier, int)} but also accepts
   * files built with an older, shorter template: {@code legacyHeaderKeys} renders one extra Chinese
   * and one extra accepted header variant (task 11 §4.3: KOL files without the four new columns
   * keep working). The matched variant's column count tells the row processor which template the
   * file used. Empty when the template never changed.
   *
   * @param legacyHeaderKeys header keys of the previous template, in its column order
   */
  Long submitImport(
      TaskType type,
      List<String> headerKeys,
      List<String> legacyHeaderKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows);

  /**
   * Same as {@link #submitImport(TaskType, List, List, ImportFileDTO, Supplier, int)} but stores
   * the submitter's parameters with the task row (task 40: order/refund imports must pass {@code
   * dataCutoffTime}). Values are simple strings; the caller serializes and validates them.
   *
   * @param params JSON object text, or null when there are no parameters
   */
  Long submitImport(
      TaskType type,
      List<String> headerKeys,
      List<String> legacyHeaderKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows,
      String params);

  /**
   * Same as {@link #submitImport(TaskType, List, ImportFileDTO, Supplier, int)} for rows the system
   * produced instead of a user upload (ADR-021: rows read from a product source sheet). The rows
   * are written into a workbook under the template header in the request language and stored as the
   * task's input file, so validation, progress, the error file and retention are identical to an
   * upload of the same rows.
   *
   * @param fileName name of the stored input file, shown as the task's source
   * @param rows data rows in template column order; must not be empty
   */
  Long submitRows(
      TaskType type,
      List<String> headerKeys,
      String fileName,
      List<List<String>> rows,
      Supplier<ImportRowProcessor> processor,
      int maxRows);

  /**
   * Returns an .xlsx workbook containing only the header row, rendered in the request language.
   *
   * @param headerKeys template header message keys ("import.*") in column order
   */
  byte[] template(List<String> headerKeys);

  /**
   * Same as {@link #template(List)} but marks the given 0-based columns as Excel text format so
   * long IDs stay text when operators fill the template.
   *
   * @param textColumns 0-based column indexes that must use the {@code @} text format
   */
  byte[] template(List<String> headerKeys, Collection<Integer> textColumns);
}
