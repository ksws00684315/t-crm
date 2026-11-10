package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.ShopPerformanceService.ShopPerformanceDTO;
import com.tcrm.admin.stats.service.ShopPerformanceService.ShopRankDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.AppliedFiltersDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetaDTO;
import com.tcrm.admin.task.service.ExcelFiles;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

class ShopPerformanceExportTest {
  @Test
  void writesBoardSpecificColumnsAndKeepsUnavailableCellsBlank() throws Exception {
    var day = LocalDate.of(2026, 11, 3);
    var period = new StatsPeriod(day, day);
    var meta =
        new MetaDTO(
            period,
            period.previous(),
            "Asia/Shanghai",
            new AppliedFiltersDTO(null, null, null, "WEIDIAN"),
            DataScope.ALL,
            null);
    var zero = BigDecimal.ZERO;
    var m = new StatsOverviewService.MetricsDTO(0, 0, zero, zero, zero, zero, 0, null, null, null);
    var point = new AnalyticsRankingService.TrendPoint(day, zero);
    var row =
        new AnalyticsRankingService.Row(
            "1",
            "=item",
            "CODE",
            "Shop",
            true,
            List.of("handle"),
            1L,
            1L,
            1,
            null,
            2L,
            1L,
            0L,
            0L,
            null,
            null,
            m,
            null,
            "Category",
            List.of("Top"),
            List.of(point),
            "source-1",
            "TikTok",
            2L,
            new BigDecimal("0.15"),
            "PROMOTING");
    var result =
        new AnalyticsRankingService.Result(
            meta,
            null,
            null,
            null,
            new PageResult<>(List.of(row), 50, 2, 20),
            List.of(),
            "units",
            "asc",
            "item");
    var messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding("UTF-8");
    var service = new ShopPerformanceExport(new ExcelFiles(), messages);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    try {
      for (var board : AnalyticsRankingService.Board.values()) {
        try (var book =
            WorkbookFactory.create(new ByteArrayInputStream(service.currentPage(board, result)))) {
          var sheet = book.getSheetAt(0);
          assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("item");
          var data = sheet.getRow(sheet.getLastRowNum());
          var header = sheet.getRow(data.getRowNum() - 1);
          var labels = new java.util.ArrayList<String>();
          header.forEach(cell -> labels.add(cell.getStringCellValue()));
          assertThat(data.getLastCellNum()).isEqualTo(header.getLastCellNum());
          if (board == AnalyticsRankingService.Board.PRODUCT) {
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("'=item");
            assertThat(labels)
                .containsExactly(
                    "Rank",
                    "Order product",
                    "Channel / ID",
                    "Shop",
                    "Creators with sales",
                    "Units",
                    "Order count",
                    "Refunded units",
                    "Sales (CNY)",
                    "Completed refunds (CNY)",
                    "Net sales (CNY)",
                    "Settlement amount (CNY)");
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Weidian / source-1");
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo("Shop");
            assertThat(data.getCell(7).getStringCellValue()).isEmpty();
            assertThat(data.getCell(11).getStringCellValue()).isEmpty();
          } else if (board == AnalyticsRankingService.Board.CREATOR) {
            assertThat(labels)
                .containsExactly(
                    "Creator / social / ID",
                    "Inviter / relationship",
                    "Order count",
                    "Units",
                    "Links with sales",
                    "Sales (CNY)",
                    "Completed refunds (CNY)",
                    "Net sales (CNY)",
                    "Average order amount (CNY)",
                    "Amount refund rate",
                    "Creator commission rate",
                    "Estimated commission (CNY)",
                    "Commission paid out (CNY)",
                    "Cumulative commission (CNY)");
            assertThat(data.getCell(0).getStringCellValue())
                .isEqualTo("'=item (TikTok) / handle / CODE");
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Shop / Promoting");
            assertThat(data.getCell(4).getStringCellValue()).isEqualTo("2");
            assertThat(data.getCell(10).getStringCellValue()).isEqualTo("15%");
            for (int i = 11; i < 14; i++) {
              assertThat(data.getCell(i).getStringCellValue()).isEmpty();
            }
          } else {
            assertThat(labels)
                .containsExactly(
                    "Inviter",
                    "Status",
                    "Active creators / lifetime invites",
                    "Units",
                    "Sales (CNY)",
                    "Completed refunds (CNY)",
                    "Net sales (CNY)",
                    "Amount refund rate",
                    "Average order amount (CNY)",
                    "Prepayments paid out (CNY)",
                    "Total estimated commission (CNY)",
                    "Total paid out (CNY)");
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("'=item / CODE");
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("1 / 2");
            for (int i = 9; i < 12; i++) {
              assertThat(data.getCell(i).getStringCellValue()).isEmpty();
            }
          }
        }
      }
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }

  @Test
  void writesFilterMetadataAboveEscapedRowsAndDoesNotIncludeOtherPages() throws Exception {
    var day = LocalDate.of(2026, 11, 3);
    var period = new StatsPeriod(day, day);
    var meta =
        new MetaDTO(
            period,
            period.previous(),
            "Asia/Shanghai",
            new AppliedFiltersDTO(1L, null, null, "WEIDIAN"),
            DataScope.OWN,
            null);
    var zero = BigDecimal.ZERO;
    var row =
        new ShopRankDTO(1L, "=cmd", "SHOP-1", 1, 0, 0, 0, 0, 0, zero, zero, zero, null, null, null);
    var result =
        new ShopPerformanceDTO(
            meta,
            null,
            null,
            null,
            new PageResult<>(List.of(row), 50, 3, 20),
            List.of(),
            "netSalesAmount",
            "desc");
    var messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding("UTF-8");
    byte[] bytes;
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    try {
      bytes = new ShopPerformanceExport(new ExcelFiles(), messages).currentPage(result);
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("2026-11-03");
      assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).contains("+08:00");
      assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Shop code");
      assertThat(sheet.getRow(9).getCell(1).getCellType()).isEqualTo(CellType.STRING);
      assertThat(sheet.getRow(9).getCell(1).getStringCellValue()).isEqualTo("'=cmd");
      assertThat(sheet.getLastRowNum()).isEqualTo(9);
    }
  }
}
