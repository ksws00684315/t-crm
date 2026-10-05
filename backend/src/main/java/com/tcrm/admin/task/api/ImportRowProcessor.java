package com.tcrm.admin.task.api;

import java.util.List;

/**
 * Business-side handling of one import file. One instance serves exactly one task, row by row in
 * file order on a single worker thread, so it may keep per-file state (for example, values already
 * seen in the file).
 */
public interface ImportRowProcessor {
  /**
   * Imports one data row in its own transaction.
   *
   * @param cells trimmed cell texts in template column order; blank cells are empty strings, never
   *     null; the list size equals the template header count
   * @return Chinese failure reasons for the error file; empty when the row was imported. Data
   *     problems must be returned, not thrown; an exception counts as a failed row.
   */
  List<String> process(List<String> cells);
}
