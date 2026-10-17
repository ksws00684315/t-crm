package com.tcrm.admin.product.api;

import java.util.List;

/**
 * One cell of a product source sheet, as the Google Sheets API returns it (ADR-021).
 *
 * @param text displayed value; empty when blank
 * @param hyperlink the cell's single link, when Google reports one
 * @param formula the entered formula such as {@code =HYPERLINK("…")}; null when none
 * @param runLinks links of rich-text runs, in order; empty when none
 */
public record SheetCellDTO(String text, String hyperlink, String formula, List<String> runLinks) {}
