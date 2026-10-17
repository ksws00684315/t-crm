package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.SheetSourceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Source sheet configuration (ADR-021 §4). {@code sheet} is the sheet address or the bare ID;
 * columns are letters such as {@code E}; category and image columns are optional.
 */
public record SheetSourceRequest(
    @NotBlank @Size(max = 64) String sourceName,
    @NotBlank @Size(max = 512) String sheet,
    @NotBlank @Size(max = 100) String sheetTitle,
    @NotNull @Min(1) @Max(100) Integer headerRow,
    @NotBlank @Pattern(regexp = "[A-Z]{1,3}") String linkColumn,
    @NotBlank @Pattern(regexp = "[A-Z]{1,3}") String nameColumn,
    @NotBlank @Pattern(regexp = "[A-Z]{1,3}") String priceColumn,
    @Pattern(regexp = "[A-Z]{1,3}") String categoryColumn,
    @Pattern(regexp = "[A-Z]{1,3}") String imageColumn,
    @Min(1) Long defaultShopId,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String priceCurrency) {
  SheetSourceService.SaveCommand toCommand() {
    return new SheetSourceService.SaveCommand(
        sourceName,
        sheet,
        sheetTitle,
        headerRow,
        linkColumn,
        nameColumn,
        priceColumn,
        categoryColumn,
        imageColumn,
        defaultShopId,
        priceCurrency);
  }
}
