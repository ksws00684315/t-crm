package com.tcrm.admin.product.api;

/** Why a promo-sheet row produced no mapping (task 26). Codes are stable for the frontend. */
public enum PromoSheetSkipReason {
  /** The cell only jumps inside the workbook (for example a RETURN row). */
  IN_SHEET,
  /** Taobao / Tmall / 1688 or another non-Weidian platform. */
  NON_WEIDIAN,
  /** A link, but no Weidian item ID could be parsed from it (Discord, short links, home pages). */
  NO_ITEM_ID,
  /** The shop number is not a registered, enabled shop of this company. */
  SHOP_NOT_REGISTERED
}
