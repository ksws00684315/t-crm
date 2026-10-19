package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExcelFilesTest {
  private static final List<String> HEADERS = List.of("编号", "姓名", "邮箱");
  private static final List<String> HEADERS_EN = List.of("Code", "Name", "Email");

  /** The two template languages, as the import framework passes them to read(). */
  private static final List<List<String>> VARIANTS = List.of(HEADERS, HEADERS_EN);

  private final ExcelFiles excel = new ExcelFiles();

  private List<List<String>> read(byte[] content, int maxRows) {
    return excel.read(content, VARIANTS, maxRows).rows();
  }

  private static byte[] workbook(List<String> head, List<List<String>> rows) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head.stream().map(List::of).toList()).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }

  private static ImportFileDTO file(String name, String type, byte[] content) {
    return new ImportFileDTO(name, type, content);
  }

  private static void assertError(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(code);
  }

  @Test
  void shouldAcceptXlsx_whenNameTypeAndSignatureMatch() {
    byte[] content = workbook(HEADERS, List.of());
    excel.check(file("达人.XLSX", ImportTaskApi.XLSX_CONTENT_TYPE, content));
    excel.check(file("a.xlsx", "application/octet-stream", content));
    excel.check(file("a.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE + "; charset=binary", content));
  }

  @ParameterizedTest
  @CsvSource({"a.xls", "a.csv", "a.xlsx.exe", "xlsx"})
  void shouldRejectFile_whenExtensionIsNotXlsx(String name) {
    byte[] content = workbook(HEADERS, List.of());
    assertError(
        () -> excel.check(file(name, ImportTaskApi.XLSX_CONTENT_TYPE, content)),
        ErrorCode.TASK_FILE_INVALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/csv", "application/vnd.ms-excel", "application/zip"})
  void shouldRejectFile_whenContentTypeIsUnexpected(String type) {
    byte[] content = workbook(HEADERS, List.of());
    assertError(() -> excel.check(file("a.xlsx", type, content)), ErrorCode.TASK_FILE_INVALID);
  }

  @Test
  void shouldRejectFile_whenNameOrTypeIsMissing() {
    byte[] content = workbook(HEADERS, List.of());
    assertError(
        () -> excel.check(file(null, ImportTaskApi.XLSX_CONTENT_TYPE, content)),
        ErrorCode.TASK_FILE_INVALID);
    assertError(() -> excel.check(file("a.xlsx", null, content)), ErrorCode.TASK_FILE_INVALID);
  }

  @Test
  void shouldRejectFile_whenSignatureIsNotZip() {
    byte[] csv = "a,b,c\n".getBytes(StandardCharsets.UTF_8);
    assertError(
        () -> excel.check(file("a.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, csv)),
        ErrorCode.TASK_FILE_INVALID);
    assertError(
        () -> excel.check(file("a.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {0x50})),
        ErrorCode.TASK_FILE_INVALID);
  }

  @Test
  void shouldAcceptFile_whenSizeIsExactlyTheLimit() {
    byte[] content = new byte[(int) ExcelFiles.MAX_BYTES];
    content[0] = 0x50;
    content[1] = 0x4B;
    content[2] = 0x03;
    content[3] = 0x04;
    excel.check(file("a.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, content));
  }

  @Test
  void shouldRejectFile_whenSizeExceedsLimit() {
    byte[] content = new byte[(int) ExcelFiles.MAX_BYTES + 1];
    assertError(
        () -> excel.check(file("a.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, content)),
        ErrorCode.TASK_FILE_TOO_LARGE);
  }

  @Test
  void shouldReturnTrimmedPaddedRows_whenFileIsValid() {
    byte[] content =
        workbook(
            List.of(" 编号 ", "姓名", "邮箱"),
            List.of(List.of(" KOL0001 ", "张三", "a@example.com"), List.of("", "李四")));
    assertThat(read(content, ExcelFiles.DEFAULT_MAX_ROWS))
        .containsExactly(List.of("KOL0001", "张三", "a@example.com"), List.of("", "李四", ""));
  }

  @Test
  void shouldIgnoreExtraColumns_whenRowIsWiderThanHeader() {
    byte[] content = workbook(HEADERS, List.of(List.of("1", "2", "3", "4")));
    assertThat(read(content, ExcelFiles.DEFAULT_MAX_ROWS)).containsExactly(List.of("1", "2", "3"));
  }

  @Test
  void shouldSkipBlankRows_whenReading() {
    byte[] content =
        workbook(HEADERS, List.of(List.of("a", "b", "c"), List.of(" ", "", " "), List.of("d")));
    assertThat(read(content, ExcelFiles.DEFAULT_MAX_ROWS)).hasSize(2);
  }

  @Test
  void shouldRejectFile_whenHeaderDiffers() {
    byte[] reordered = workbook(List.of("姓名", "编号", "邮箱"), List.of(List.of("a", "b", "c")));
    assertError(
        () -> read(reordered, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_HEADER_MISMATCH);
    byte[] missing = workbook(List.of("编号", "姓名"), List.of(List.of("a", "b")));
    assertError(
        () -> read(missing, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_HEADER_MISMATCH);
    byte[] extra = workbook(List.of("编号", "姓名", "邮箱", "备注"), List.of(List.of("a")));
    assertError(
        () -> read(extra, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_HEADER_MISMATCH);
  }

  @Test
  void shouldRejectFile_whenSheetIsEmpty() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).sheet("Sheet1").doWrite(List.<List<String>>of());
    assertError(
        () -> read(out.toByteArray(), ExcelFiles.DEFAULT_MAX_ROWS),
        ErrorCode.TASK_FILE_HEADER_MISMATCH);
  }

  @Test
  void shouldRejectFile_whenThereAreNoDataRows() {
    byte[] content = workbook(HEADERS, List.of());
    assertError(() -> read(content, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_EMPTY);
  }

  @Test
  void shouldAcceptFile_whenRowCountIsExactlyTheLimit() {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < ExcelFiles.DEFAULT_MAX_ROWS; i++) {
      rows.add(List.of("c" + i, "n", "e"));
    }
    assertThat(read(workbook(HEADERS, rows), ExcelFiles.DEFAULT_MAX_ROWS))
        .hasSize(ExcelFiles.DEFAULT_MAX_ROWS);
  }

  @Test
  void shouldRejectFile_whenRowCountExceedsLimit() {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i <= ExcelFiles.DEFAULT_MAX_ROWS; i++) {
      rows.add(List.of("c" + i, "n", "e"));
    }
    byte[] content = workbook(HEADERS, rows);
    assertError(
        () -> read(content, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_TOO_MANY_ROWS);
  }

  @Test
  void shouldRejectFile_whenZipIsNotAWorkbook() {
    byte[] content = {0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8};
    assertError(() -> read(content, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_INVALID);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"=1+1|'=1+1", "+86|'+86", "-5|'-5", "@SUM(A1)|'@SUM(A1)", "a=b|a=b", "1-2|1-2"},
      delimiter = '|',
      quoteCharacter = '"')
  void shouldEscapeFormulaPrefix_whenCellStartsWithFormulaCharacter(String raw, String escaped) {
    assertThat(ExcelFiles.escape(raw)).isEqualTo(escaped);
  }

  @Test
  void shouldKeepEmptyValues_whenEscaping() {
    assertThat(ExcelFiles.escape("")).isEmpty();
    assertThat(ExcelFiles.escape(null)).isNull();
  }

  @Test
  void shouldWriteEscapedCells_whenWritingWorkbook() {
    byte[] content =
        excel.write(List.of("A", "=H"), List.of(List.of("=HYPERLINK(\"x\")", "ok"), List.of()));
    List<Map<Integer, String>> rows =
        FastExcel.read(new ByteArrayInputStream(content)).sheet(0).headRowNumber(0).doReadSync();
    assertThat(rows.get(0)).containsEntry(0, "A").containsEntry(1, "'=H");
    assertThat(rows.get(1)).containsEntry(0, "'=HYPERLINK(\"x\")").containsEntry(1, "ok");
  }

  @Test
  void shouldAcceptFile_whenRowCountIsExactlyCustomLimit() {
    List<List<String>> rows = List.of(List.of("1", "a", "b"), List.of("2", "c", "d"));
    assertThat(read(workbook(HEADERS, rows), 2)).hasSize(2);
  }

  @Test
  void shouldRejectFile_whenRowCountExceedsCustomLimit() {
    List<List<String>> rows =
        List.of(List.of("1", "a", "b"), List.of("2", "c", "d"), List.of("3", "e", "f"));
    assertError(() -> read(workbook(HEADERS, rows), 2), ErrorCode.TASK_FILE_TOO_MANY_ROWS);
  }

  @Test
  void shouldMatchEitherTemplateLanguage_whenHeaderRowEqualsOneVariant() {
    assertThat(excel.read(workbook(HEADERS, List.of(List.of("1"))), VARIANTS, 10).matchedHeaders())
        .isEqualTo(HEADERS);
    assertThat(
            excel.read(workbook(HEADERS_EN, List.of(List.of("1"))), VARIANTS, 10).matchedHeaders())
        .isEqualTo(HEADERS_EN);
  }

  @Test
  void shouldRejectFile_whenHeaderMixesLanguages() {
    byte[] mixed = workbook(List.of("编号", "Name", "邮箱"), List.of(List.of("a", "b", "c")));
    assertError(
        () -> read(mixed, ExcelFiles.DEFAULT_MAX_ROWS), ErrorCode.TASK_FILE_HEADER_MISMATCH);
  }

  @Test
  void shouldMarkSelectedColumnsAsText_whenWritingTemplate() throws Exception {
    byte[] content = excel.write(List.of("店铺ID", "名称", "商品ID"), List.of(), Set.of(0, 2));
    try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = wb.getSheetAt(0);
      Row header = sheet.getRow(0);
      DataFormatter formatter = new DataFormatter();
      assertThat(formatter.formatCellValue(header.getCell(0))).isEqualTo("店铺ID");
      // Default column style is what Excel applies when operators type into empty cells.
      assertThat(sheet.getColumnStyle(0).getDataFormatString()).isEqualTo("@");
      assertThat(sheet.getColumnStyle(2).getDataFormatString()).isEqualTo("@");
      assertThat(sheet.getColumnStyle(1).getDataFormatString()).isNotEqualTo("@");
    }
  }

  @Test
  void shouldAcceptCsv_whenAllowed() {
    byte[] csv = "编号,姓名,邮箱\nKOL1,张三,a@example.com\n".getBytes(StandardCharsets.UTF_8);
    excel.check(file("orders.csv", "text/csv", csv), true);
    excel.check(file("orders.CSV", "application/octet-stream", csv), true);
  }

  @Test
  void shouldRejectCsv_whenNotAllowed() {
    byte[] csv = "编号,姓名,邮箱\nKOL1,张三,a@example.com\n".getBytes(StandardCharsets.UTF_8);
    assertError(
        () -> excel.check(file("orders.csv", "text/csv", csv)), ErrorCode.TASK_FILE_INVALID);
  }

  @Test
  void shouldReadCsv_matchingExcelRows_withBomQuotesAndLongIds() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    String body = "编号,姓名,邮箱\n1234567890123456789,\"张,三\",\"a\"\"b@example.com\"\n";
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    byte[] csv = new byte[bom.length + bodyBytes.length];
    System.arraycopy(bom, 0, csv, 0, bom.length);
    System.arraycopy(bodyBytes, 0, csv, bom.length, bodyBytes.length);

    ExcelFiles.SheetData sheet = excel.read(csv, VARIANTS, 10, true);
    assertThat(sheet.matchedHeaders()).isEqualTo(HEADERS);
    assertThat(sheet.rows())
        .containsExactly(List.of("1234567890123456789", "张,三", "a\"b@example.com"));
  }

  @Test
  void shouldRejectCsv_whenHeaderMismatches() {
    byte[] csv = "姓名,编号,邮箱\na,b,c\n".getBytes(StandardCharsets.UTF_8);
    assertError(() -> excel.read(csv, VARIANTS, 10, true), ErrorCode.TASK_FILE_HEADER_MISMATCH);
  }

  @Test
  void shouldRejectCsvContent_whenReadingAsXlsx() {
    byte[] csv = "编号,姓名,邮箱\nKOL1,张三,a@example.com\n".getBytes(StandardCharsets.UTF_8);
    assertError(() -> excel.read(csv, VARIANTS, 10), ErrorCode.TASK_FILE_INVALID);
    assertError(() -> excel.read(csv, VARIANTS, 10, false), ErrorCode.TASK_FILE_INVALID);
  }
}
