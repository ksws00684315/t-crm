package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.task.service.ExcelFiles;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderImportFieldsTest {
  @ParameterizedTest
  @CsvSource({
    "2026-10-17 09:05:07, 2026-10-17T01:05:07Z",
    "2026/10/17 9:05, 2026-10-17T01:05:00Z",
    "2026/10/17 9:05:07, 2026-10-17T01:05:07Z",
    "2026-10-17 9:05:07, 2026-10-17T01:05:07Z",
    "2026/1/2 3:04, 2026-01-01T19:04:00Z"
  })
  void shouldParseFlexibleBeijingTime(String text, String expectedUtc) {
    assertThat(OrderImportFields.beijingTime(text)).isEqualTo(java.time.Instant.parse(expectedUtc));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "not-a-date", "2026-13-01 00:00:00", "2026/10/17", "2026-10-17T09:05:07"})
  void shouldRejectInvalidBeijingTime(String text) {
    assertThat(OrderImportFields.beijingTime(text.isEmpty() ? " " : text)).isNull();
  }

  @Test
  void shouldReadRealPoiDateCell_viaExcelFiles() throws Exception {
    byte[] bytes;
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      var sheet = wb.createSheet("Sheet1");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("编号");
      header.createCell(1).setCellValue("姓名");
      header.createCell(2).setCellValue("邮箱");
      // Extra column not in header variants for ExcelFiles - use matching 3-col headers
      // Write a 3-col workbook where middle cell is a real date for a separate FastExcel read.
      // For ExcelFiles we need exact header match; put date in "邮箱" column as datetime display.
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("C1");
      row.createCell(1).setCellValue("N1");
      var dateCell = row.createCell(2);
      Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
      cal.clear();
      cal.set(2026, Calendar.OCTOBER, 17, 9, 5, 7);
      dateCell.setCellValue(cal.getTime());
      CellStyle style = wb.createCellStyle();
      DataFormat format = wb.createDataFormat();
      style.setDataFormat(format.getFormat("yyyy/m/d h:mm:ss"));
      dateCell.setCellStyle(style);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      wb.write(out);
      bytes = out.toByteArray();
    }
    ExcelFiles excel = new ExcelFiles();
    List<List<String>> rows = excel.read(bytes, List.of(List.of("编号", "姓名", "邮箱")), 10).rows();
    assertThat(rows).hasSize(1);
    String display = rows.getFirst().get(2);
    assertThat(OrderImportFields.beijingTime(display))
        .isEqualTo(
            LocalDateTime.of(2026, 10, 17, 9, 5, 7).atZone(OrderImportFields.BEIJING).toInstant());
  }

  @Test
  void shouldParseRefundStyleSlashTimeWithoutSeconds() {
    assertThat(OrderImportFields.beijingTime("2026/10/16 13:00"))
        .isEqualTo(java.time.Instant.parse("2026-10-16T05:00:00Z"));
  }
}
