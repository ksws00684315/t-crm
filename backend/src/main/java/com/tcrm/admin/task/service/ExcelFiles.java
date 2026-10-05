package com.tcrm.admin.task.service;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import cn.idev.excel.exception.ExcelAnalysisStopException;
import cn.idev.excel.support.ExcelTypeEnum;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Generic Excel handling for imports (spec 06 §3): file-level checks, header check, reading rows as
 * trimmed text, and writing workbooks with formula-injection escaping.
 */
@Component
public class ExcelFiles {
  /** Upload size limit; Spring multipart is configured with the same value. */
  public static final long MAX_BYTES = 20L * 1024 * 1024;

  /** Default data row limit (KOL import); callers pass a per-type limit to {@link #read}. */
  public static final int DEFAULT_MAX_ROWS = 2_000;

  private static final Set<String> ACCEPTED_TYPES =
      Set.of(ImportTaskApi.XLSX_CONTENT_TYPE, "application/octet-stream");
  private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
  private static final String SHEET = "Sheet1";

  /** Rejects anything that is not a plausible .xlsx upload, before parsing it. */
  public void check(ImportFileDTO file) {
    String name = file.fileName() == null ? "" : file.fileName().toLowerCase(Locale.ROOT);
    String type = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
    int semicolon = type.indexOf(';');
    if (semicolon >= 0) {
      type = type.substring(0, semicolon).trim();
    }
    if (!name.endsWith(".xlsx") || !ACCEPTED_TYPES.contains(type)) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    byte[] content = file.content();
    if (content.length > MAX_BYTES) {
      throw new BusinessException(ErrorCode.TASK_FILE_TOO_LARGE);
    }
    if (content.length < ZIP_SIGNATURE.length) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
      if (content[i] != ZIP_SIGNATURE[i]) {
        throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
      }
    }
  }

  /**
   * Reads the first sheet: the first row must equal {@code headers}; the following non-blank rows
   * are returned as trimmed texts padded to the header count (extra columns are ignored).
   *
   * @throws BusinessException TASK_FILE_INVALID, TASK_FILE_HEADER_MISMATCH, TASK_FILE_EMPTY or
   *     TASK_FILE_TOO_MANY_ROWS
   */
  public List<List<String>> read(byte[] content, List<String> headers, int maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be positive");
    }
    RowCollector collector = new RowCollector(headers, maxRows);
    try {
      FastExcel.read(new ByteArrayInputStream(content), collector)
          .excelType(ExcelTypeEnum.XLSX)
          .ignoreEmptyRow(true)
          .sheet(0)
          .headRowNumber(1)
          .doRead();
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      BusinessException cause = findBusinessCause(e);
      if (cause != null) {
        throw cause;
      }
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    if (collector.tooMany) {
      throw new BusinessException(ErrorCode.TASK_FILE_TOO_MANY_ROWS);
    }
    if (!collector.headerChecked) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    if (collector.rows.isEmpty()) {
      throw new BusinessException(ErrorCode.TASK_FILE_EMPTY);
    }
    return collector.rows;
  }

  /** Writes one sheet with a header row; every cell is escaped against formula injection. */
  public byte[] write(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(h -> List.of(escape(h))).toList();
    List<List<String>> data =
        rows.stream().map(row -> row.stream().map(ExcelFiles::escape).toList()).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).excelType(ExcelTypeEnum.XLSX).head(head).sheet(SHEET).doWrite(data);
    return out.toByteArray();
  }

  /**
   * Prefixes {@code '} to text starting with {@code =}, {@code +}, {@code -} or {@code @} so that
   * spreadsheet programs never evaluate it as a formula (spec 06 §3).
   */
  public static String escape(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + value : value;
  }

  private static BusinessException findBusinessCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BusinessException business) {
        return business;
      }
    }
    return null;
  }

  /** Collects rows; stops reading as soon as the row limit is exceeded. */
  private static final class RowCollector extends AnalysisEventListener<Map<Integer, String>> {
    private final List<String> headers;
    private final int maxRows;
    private final List<List<String>> rows = new ArrayList<>();
    private boolean headerChecked;
    private boolean tooMany;

    RowCollector(List<String> headers, int maxRows) {
      this.headers = headers;
      this.maxRows = maxRows;
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> head, AnalysisContext context) {
      List<String> actual = cells(head, maxIndex(head) + 1);
      while (!actual.isEmpty() && actual.getLast().isEmpty()) {
        actual.removeLast();
      }
      if (!actual.equals(headers)) {
        throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
      }
      headerChecked = true;
    }

    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext context) {
      if (!headerChecked) {
        throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
      }
      List<String> row = cells(data, headers.size());
      if (row.stream().allMatch(String::isEmpty)) {
        return;
      }
      if (rows.size() >= maxRows) {
        tooMany = true;
        throw new ExcelAnalysisStopException();
      }
      rows.add(row);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      // Nothing to flush: rows are kept in memory and bounded by maxRows.
    }

    private static int maxIndex(Map<Integer, String> cells) {
      return cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    private static List<String> cells(Map<Integer, String> source, int size) {
      List<String> result = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        String value = source.get(i);
        result.add(value == null ? "" : value.strip());
      }
      return result;
    }
  }
}
