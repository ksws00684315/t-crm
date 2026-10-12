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

  /** A parsed sheet: the header variant the file matched, and its data rows. */
  public record SheetData(List<String> matchedHeaders, List<List<String>> rows) {}

  /**
   * Reads the first sheet: the header row must equal one of {@code headerVariants} exactly (the
   * Chinese and the English template; mixing languages is a mismatch); the following non-blank rows
   * are returned as trimmed texts padded to the header count (extra columns are ignored).
   *
   * @throws BusinessException TASK_FILE_INVALID, TASK_FILE_HEADER_MISMATCH, TASK_FILE_EMPTY or
   *     TASK_FILE_TOO_MANY_ROWS
   */
  public SheetData read(byte[] content, List<List<String>> headerVariants, int maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be positive");
    }
    RowCollector collector = new RowCollector(headerVariants, maxRows);
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
    if (collector.matched == null) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    if (collector.rows.isEmpty()) {
      throw new BusinessException(ErrorCode.TASK_FILE_EMPTY);
    }
    return new SheetData(collector.matched, collector.rows);
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
    private final List<List<String>> headerVariants;
    private final int maxRows;
    private final List<List<String>> rows = new ArrayList<>();
    private List<String> matched;
    private boolean tooMany;

    RowCollector(List<List<String>> headerVariants, int maxRows) {
      this.headerVariants = headerVariants;
      this.maxRows = maxRows;
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> head, AnalysisContext context) {
      List<String> actual = cells(head, maxIndex(head) + 1);
      while (!actual.isEmpty() && actual.getLast().isEmpty()) {
        actual.removeLast();
      }
      matched =
          headerVariants.stream()
              .filter(variant -> actual.equals(variant))
              .findFirst()
              .orElse(null);
      if (matched == null) {
        throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
      }
    }

    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext context) {
      if (matched == null) {
        throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
      }
      List<String> row = cells(data, matched.size());
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
