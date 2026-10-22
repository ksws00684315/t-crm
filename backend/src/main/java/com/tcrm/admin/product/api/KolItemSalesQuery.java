package com.tcrm.admin.product.api;

import java.util.Collection;
import java.util.List;

/**
 * Sales totals for the creator-item page. Implemented by the order module so product does not read
 * order tables.
 */
public interface KolItemSalesQuery {
  /**
   * One row per mapping that has order lines. Mappings with no lines are omitted. An empty
   * collection returns an empty list without querying.
   *
   * @param kolItemIds current page of {@code crm_kol_item.id}
   */
  List<KolItemSalesTotal> sumByKolItemIds(Collection<Long> kolItemIds);
}
