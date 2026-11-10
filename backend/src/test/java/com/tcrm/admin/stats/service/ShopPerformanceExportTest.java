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
