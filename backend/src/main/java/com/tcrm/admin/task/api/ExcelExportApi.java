package com.tcrm.admin.task.api;

import java.util.List;

/** Writes an escaped workbook with filter metadata above the table headers. */
public interface ExcelExportApi {
  byte[] export(List<List<String>> metadata, List<String> headers, List<List<String>> rows);
}
