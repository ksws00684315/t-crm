package com.tcrm.admin.product.api;

/**
 * One link-bearing row of an uploaded promo sheet (task 26, ADR-024), as parsed by the KOL module
 * and classified by the product module.
 *
 * @param sheet worksheet display name, for skip samples
 * @param row 1-based row number
 * @param link the row's link (cell hyperlink, {@code HYPERLINK} formula or http text); null for an
 *     in-sheet jump row
 * @param inSheetJump the row only carries a {@code location} hyperlink inside the workbook
 * @param name name snapshot from the row's {@code NAME} / {@code 名称} column; null when the sheet
 *     has no such column or the cell is empty (already truncated to 255 characters)
 */
public record PromoSheetRowDTO(
    String sheet, int row, String link, boolean inSheetJump, String name) {}
