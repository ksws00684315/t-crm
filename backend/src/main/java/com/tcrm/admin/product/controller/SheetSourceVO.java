package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.SheetSourceService;
import java.time.Instant;

/** Source sheet with its last read summary; {@code lastError} is an error code name or null. */
public record SheetSourceVO(
    Long id,
    String sourceName,
    String spreadsheetId,
    String sheetTitle,
    int headerRow,
    String linkColumn,
    String nameColumn,
    String priceColumn,
    String categoryColumn,
    String imageColumn,
    Long defaultShopId,
    String defaultShopName,
    String priceCurrency,
    int status,
    Instant lastReadAt,
    Long lastTaskId,
    Integer lastRowCount,
    SheetSourceService.Skipped lastSkipped,
    String lastError) {
  static SheetSourceVO from(SheetSourceService.SourceDTO d) {
    return new SheetSourceVO(
        d.id(),
        d.sourceName(),
        d.spreadsheetId(),
        d.sheetTitle(),
        d.headerRow(),
        d.linkColumn(),
        d.nameColumn(),
        d.priceColumn(),
        d.categoryColumn(),
        d.imageColumn(),
        d.defaultShopId(),
        d.defaultShopName(),
        d.priceCurrency(),
        d.status(),
        d.lastReadAt(),
        d.lastTaskId(),
        d.lastRowCount(),
        d.lastSkipped(),
        d.lastError());
  }
}
