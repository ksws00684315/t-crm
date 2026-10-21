package com.tcrm.admin.product.api;

import java.util.Collection;
import java.util.List;

/**
 * Visit counts for the creator-item detail. Implemented by the link module so product does not read
 * the click log. Counts follow {@code kol_item_id}, which is the page's trace key.
 */
public interface KolItemVisitQuery {
  /**
   * One row per mapping that has a valid click. Mappings with none are omitted. An empty collection
   * returns an empty list without querying.
   *
   * @param kolItemIds {@code crm_kol_item.id} values
   */
  List<KolItemVisitTotal> countByKolItemIds(Collection<Long> kolItemIds);
}
