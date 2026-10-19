package com.tcrm.admin.task.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal UTF-8 CSV reader for order/refund imports (task 28 §3.6). No third-party dependency:
 * comma-separated, double-quote escaping, optional BOM. Every cell stays text so long ids are not
 * coerced to scientific notation.
 */
final class CsvFiles {
  private CsvFiles() {}

  static ExcelFiles.SheetData read(byte[] content, List<List<String>> headerVariants, int maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be positive");
    }
    String text = decodeUtf8(content);
    List<List<String>> rows = parse(text);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    List<String> header = trimTrailingEmpty(rows.get(0));
    List<String> matched =
        headerVariants.stream().filter(variant -> header.equals(variant)).findFirst().orElse(null);
    if (matched == null) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    List<List<String>> data = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      List<String> raw = rows.get(i);
      List<String> padded = pad(raw, matched.size());
      if (padded.stream().allMatch(String::isEmpty)) {
        continue;
      }
      if (data.size() >= maxRows) {
        throw new BusinessException(ErrorCode.TASK_FILE_TOO_MANY_ROWS);
      }
      data.add(padded);
    }
    if (data.isEmpty()) {
      throw new BusinessException(ErrorCode.TASK_FILE_EMPTY);
    }
    return new ExcelFiles.SheetData(matched, data);
  }

  private static String decodeUtf8(byte[] content) {
    int offset = 0;
    if (content.length >= 3
        && (content[0] & 0xFF) == 0xEF
        && (content[1] & 0xFF) == 0xBB
        && (content[2] & 0xFF) == 0xBF) {
      offset = 3;
    }
    return new String(content, offset, content.length - offset, StandardCharsets.UTF_8);
  }

  /** Parses the whole document into rows of cells (already stripped). */
  static List<List<String>> parse(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> current = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean inQuotes = false;
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < n && text.charAt(i + 1) == '"') {
            cell.append('"');
            i += 2;
            continue;
          }
          inQuotes = false;
          i++;
          continue;
        }
        cell.append(c);
        i++;
        continue;
      }
      if (c == '"') {
        inQuotes = true;
        i++;
        continue;
      }
      if (c == ',') {
        current.add(cell.toString().strip());
        cell.setLength(0);
        i++;
        continue;
      }
      if (c == '\r') {
        current.add(cell.toString().strip());
        cell.setLength(0);
        rows.add(current);
        current = new ArrayList<>();
        i++;
        if (i < n && text.charAt(i) == '\n') {
          i++;
        }
        continue;
      }
      if (c == '\n') {
        current.add(cell.toString().strip());
        cell.setLength(0);
        rows.add(current);
        current = new ArrayList<>();
        i++;
        continue;
      }
      cell.append(c);
      i++;
    }
    if (inQuotes) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    // Trailing cell / last line without newline
    if (cell.length() > 0 || !current.isEmpty() || (n > 0 && text.charAt(n - 1) == ',')) {
      current.add(cell.toString().strip());
      rows.add(current);
    } else if (!text.isEmpty() && (text.endsWith("\n") || text.endsWith("\r"))) {
      // already flushed by newline handlers
    }
    return rows;
  }

  private static List<String> trimTrailingEmpty(List<String> row) {
    List<String> copy = new ArrayList<>(row);
    while (!copy.isEmpty() && copy.getLast().isEmpty()) {
      copy.removeLast();
    }
    return copy;
  }

  private static List<String> pad(List<String> raw, int size) {
    List<String> out = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      out.add(i < raw.size() ? raw.get(i) : "");
    }
    return out;
  }
}
