package com.tcrm.admin.product.api;

import com.tcrm.admin.shared.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Read access to kol item mappings for other modules (task 06): the {@code link} module builds the
 * Redis link index and the link list from current mappings, and resolves click events to mappings.
 * Only current mappings ({@code valid_to IS NULL}) of undeleted products and shops are returned by
 * the {@code *CurrentLink*} methods.
 */
public interface KolItemApi {
  /**
   * Returns all current mappings of the KOL (at most about 20,000) in no particular order.
   *
   * @param kolId KOL ID; unknown IDs return an empty list
   */
  List<KolItemLinkDTO> listCurrentLinksByKol(Long kolId);

  /**
   * Returns the current mappings of the KOL whose exclusive item belongs to the shop.
   *
   * @param kolId KOL ID
   * @param shopId shop of the exclusive item ({@code crm_kol_item.shop_id})
   */
  List<KolItemLinkDTO> listCurrentLinksByKolAndShop(Long kolId, Long shopId);

  /**
   * Returns all current mappings of the original product (at most about 500).
   *
   * @param productId original product ID
   */
  List<KolItemLinkDTO> listCurrentLinksByProduct(Long productId);

  /**
   * Pages KOL ids that have a current mapping of the original product, in ascending id order. Used
   * to refresh the link index without loading every mapping of a popular product at once.
   *
   * @param productId original product ID
   * @param afterKolId exclusive lower bound; null starts at the first id
   * @param limit maximum number of ids (positive)
   */
  List<Long> pageKolIdsWithCurrentProduct(Long productId, Long afterKolId, int limit);

  /**
   * Pages KOL ids that have a current mapping whose exclusive item belongs to the shop, in
   * ascending id order.
   *
   * @param shopId shop of the exclusive item ({@code crm_kol_item.shop_id})
   * @param afterKolId exclusive lower bound; null starts at the first id
   * @param limit maximum number of ids (positive)
   */
  List<Long> pageKolIdsWithCurrentShop(Long shopId, Long afterKolId, int limit);

  /**
   * Returns the current mapping of the KOL and original product, or null when there is none.
   *
   * @param kolId KOL ID
   * @param productId original product ID
   */
  KolItemLinkDTO getCurrentLink(Long kolId, Long productId);

  /**
   * Pages the current mappings of the KOL ordered by original product code (then ID).
   *
   * @param kolId KOL ID
   * @param productCode exact original product code (case-insensitive), or null for all
   * @param page page number starting at 1
   * @param size page size (1..100)
   */
  PageResult<KolItemLinkDTO> pageCurrentLinksByKol(
      Long kolId, String productCode, int page, int size);

  /**
   * Returns the original product code of an undeleted or deleted product, or null when the ID is
   * unknown. Used to remove the link index field once a mapping is closed.
   *
   * @param productId original product ID
   */
  String getProductCode(Long productId);

  /**
   * Returns the mappings (current or closed) with these IDs; unknown IDs are skipped. An empty
   * collection returns an empty list without querying.
   *
   * @param kolItemIds {@code crm_kol_item.id} values
   */
  List<KolItemRefDTO> listRefsByIds(Collection<Long> kolItemIds);

  /**
   * Exclusive item IDs of every mapping (current and closed) of the KOL and original product (task
   * 11 §4.4). The link index drops a {@code linkitem} field for each of them before writing the
   * current one, so a replaced or closed mapping leaves no stale field.
   */
  List<String> listItemIdsByKolProduct(Long kolId, Long productId);

  /**
   * Exclusive item IDs of every mapping (current and closed) of the KOL (task 11 §4.4). The link
   * index drops all of the KOL's {@code linkitem} fields before rewriting the current ones.
   */
  List<String> listItemIdsByKol(Long kolId);

  /**
   * The subset of exclusive item IDs that still has a current mapping of any KOL (task 11 §4.4).
   * The full rebuild checks every orphan candidate against the database before deleting, so a
   * mapping committed while the rebuild runs is never dropped. An empty collection returns an empty
   * set without querying.
   */
  Set<String> filterCurrentItemIds(Collection<String> weidianItemIds);
}
