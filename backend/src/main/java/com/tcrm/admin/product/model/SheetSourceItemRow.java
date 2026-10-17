package com.tcrm.admin.product.model;

/** One product seen in a source sheet read; upserted into crm_sheet_source_item. */
public record SheetSourceItemRow(
    Long id,
    Long tenantId,
    Long sourceId,
    Long shopId,
    String weidianItemId,
    Integer sheetRow,
    Integer lastReadNo) {}
