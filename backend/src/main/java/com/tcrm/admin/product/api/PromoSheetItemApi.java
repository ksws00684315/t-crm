package com.tcrm.admin.product.api;

import java.util.List;

/**
 * Promo-sheet classification and confirmation for {@code crm_kol_item} (task 26, ADR-024). The KOL
 * module parses the uploaded workbook and calls these; this module owns the mapping table.
 */
public interface PromoSheetItemApi {

  /**
   * Classifies parsed promo-sheet rows for one KOL without writing anything. Runs in the caller's
   * tenant scope.
   *
   * @param kolId the KOL the sheet was uploaded for
   * @param rows parsed rows, in sheet order
   */
  PromoSheetClassification classify(Long kolId, List<PromoSheetRowDTO> rows);

  /**
   * Re-validates the rows at confirmation time and writes the additions as mappings with source
   * {@code SHEET_UPLOAD} ({@code valid_from = now}, original product filled when one exists for the
   * shop item). Shop items that became owned by another KOL since the preview are reported as
   * conflicts and skipped. The KOL's status is never touched (ADR-023).
   *
   * @param kolId the KOL the sheet was uploaded for
   * @param rows parsed rows, in sheet order
   */
  PromoSheetConfirmResult confirm(Long kolId, List<PromoSheetRowDTO> rows);
}
