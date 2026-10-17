package com.tcrm.admin.product.api;

import java.util.List;
import java.util.Map;

/**
 * Reads product source sheets (ADR-021). Defined here and implemented by the {@code integration}
 * module's Google client with the current company's service account, so that {@code product} does
 * not depend on {@code integration} (which already depends on {@code product.api}). The spreadsheet
 * must be shared with that account; nothing is written.
 */
public interface SourceSheetReader {
  /**
   * Reads the given columns of one tab, from {@code fromRow} through {@code fromRow + maxRows}.
   *
   * @param spreadsheetId spreadsheet ID from the sheet address
   * @param sheetTitle tab name
   * @param fromRow first row to read, 1-based
   * @param maxRows rows to read; one more is requested so callers can detect an oversized sheet
   * @param columns column letters such as {@code "E"}
   * @return per column letter, the non-empty cells keyed by 1-based row number
   * @throws com.tcrm.admin.shared.BusinessException GOOGLE_* when the credential is missing or
   *     rejected, the sheet is not shared, missing, or Google fails
   */
  Map<String, Map<Integer, SheetCellDTO>> readColumns(
      String spreadsheetId, String sheetTitle, int fromRow, int maxRows, List<String> columns);
}
