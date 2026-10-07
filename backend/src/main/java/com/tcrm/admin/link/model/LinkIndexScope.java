package com.tcrm.admin.link.model;

/** What a {@code crm_link_index_outbox} row asks the background sync to refresh. */
public enum LinkIndexScope {
  /** Rewrite every current field of this KOL. */
  KOL,
  /** Rewrite every current field of each KOL that maps this original product. */
  PRODUCT,
  /** Rewrite every current field of each KOL that maps an exclusive item of this shop. */
  SHOP,
  /** Rewrite or delete the single field of this KOL and original product. */
  MAPPING
}
