package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemLinkRow;
import com.tcrm.admin.product.model.KolItemOwnerRow;
import com.tcrm.admin.product.model.KolItemShopRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolItemMapper extends BaseMapper<KolItemDO> {
  /** Current mapping that occupies this exclusive item, or null. */
  @Select(
      """
      SELECT * FROM crm_kol_item
      WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND valid_to IS NULL
      """)
  KolItemDO findCurrentByShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /** Current mapping for this kol + original product, or null. */
  @Select(
      """
      SELECT * FROM crm_kol_item
      WHERE kol_id = #{kolId} AND product_id = #{productId} AND valid_to IS NULL
      """)
  KolItemDO findCurrentByKolProduct(@Param("kolId") Long kolId, @Param("productId") Long productId);

  /** Current exclusive mapping for this Weidian item id (any shop), or null. */
  @Select(
      """
      SELECT * FROM crm_kol_item
      WHERE weidian_item_id = #{weidianItemId} AND valid_to IS NULL
      LIMIT 1
      """)
  KolItemDO findCurrentByWeidianItem(@Param("weidianItemId") String weidianItemId);

  /** True when any current mapping uses this shop + weidian item as an exclusive item. */
  @Select(
      """
      SELECT EXISTS (
        SELECT 1 FROM crm_kol_item
        WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND valid_to IS NULL
      )
      """)
  boolean existsCurrentShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /**
   * Current mappings joined with their shop and, when present, the undeleted original product (task
   * 06 link index). The product join is outer since task 26: a promo-sheet mapping may have no
   * original product, yet its {@code linkitem} field must exist for the {@code /i/{id}} redirect; a
   * mapping whose product was deleted keeps the old behaviour and drops out. Every query below
   * starts from an index on crm_kol_item: kol → uk_crm_kol_item_kol_product, product →
   * idx_crm_kol_item_product.
   */
  String LINK_COLUMNS =
      """
      SELECT ki.id AS kol_item_id, ki.kol_id, ki.product_id, p.product_code,
             p.name AS product_name, ki.shop_id, ki.weidian_item_id,
             p.status AS product_status, s.status AS shop_status
      FROM crm_kol_item ki
      LEFT JOIN crm_product p ON p.id = ki.product_id AND p.is_deleted = FALSE
      JOIN crm_shop s ON s.id = ki.shop_id AND s.is_deleted = FALSE
      WHERE (ki.product_id IS NULL OR p.id IS NOT NULL)
      """;

  @Select(LINK_COLUMNS + " AND ki.kol_id = #{kolId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByKol(@Param("kolId") Long kolId);

  @Select(
      LINK_COLUMNS + " AND ki.kol_id = #{kolId} AND ki.shop_id = #{shopId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByKolAndShop(
      @Param("kolId") Long kolId, @Param("shopId") Long shopId);

  @Select(LINK_COLUMNS + " AND ki.product_id = #{productId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByProduct(@Param("productId") Long productId);

  /**
   * Keyset page of distinct KOL ids. The {@code product_id} predicate uses {@code
   * idx_crm_kol_item_product}. A null {@code afterKolId} is the first page.
   */
  @Select(
      """
      SELECT DISTINCT kol_id FROM crm_kol_item
      WHERE product_id = #{productId}
        AND valid_to IS NULL
        AND (CAST(#{afterKolId,jdbcType=BIGINT} AS BIGINT) IS NULL OR kol_id > #{afterKolId,jdbcType=BIGINT})
      ORDER BY kol_id
      LIMIT #{limit}
      """)
  List<Long> pageKolIdsWithCurrentProduct(
      @Param("productId") Long productId,
      @Param("afterKolId") Long afterKolId,
      @Param("limit") int limit);

  /**
   * Keyset page of distinct KOL ids. The shop predicate uses the partial unique index {@code
   * uk_crm_kol_item_shop_item} ({@code shop_id} leading, {@code valid_to IS NULL}).
   */
  @Select(
      """
      SELECT DISTINCT kol_id FROM crm_kol_item
      WHERE shop_id = #{shopId}
        AND valid_to IS NULL
        AND (CAST(#{afterKolId,jdbcType=BIGINT} AS BIGINT) IS NULL OR kol_id > #{afterKolId,jdbcType=BIGINT})
      ORDER BY kol_id
      LIMIT #{limit}
      """)
  List<Long> pageKolIdsWithCurrentShop(
      @Param("shopId") Long shopId,
      @Param("afterKolId") Long afterKolId,
      @Param("limit") int limit);

  @Select(
      LINK_COLUMNS
          + " AND ki.kol_id = #{kolId} AND ki.product_id = #{productId} AND ki.valid_to IS NULL")
  KolItemLinkRow getCurrentLink(@Param("kolId") Long kolId, @Param("productId") Long productId);

  /** Link list page of one KOL ordered by product code; productCode null means all products. */
  @Select(
      "<script>"
          + LINK_COLUMNS
          + " AND ki.kol_id = #{kolId} AND ki.valid_to IS NULL"
          + "<if test='productCode != null'> AND p.product_code = #{productCode}</if>"
          + " ORDER BY p.product_code ASC, ki.id ASC"
          + "</script>")
  IPage<KolItemLinkRow> pageCurrentLinksByKol(
      Page<KolItemLinkRow> page,
      @Param("kolId") Long kolId,
      @Param("productCode") String productCode);

  /**
   * Exclusive item IDs of every mapping (current and closed) of the KOL and original product (task
   * 11 §4.4). Used to drop stale {@code linkitem} fields after a mapping was replaced or closed;
   * uses {@code uk_crm_kol_item_kol_product} (index columns only, so closed rows are covered too).
   */
  @Select(
      """
      SELECT weidian_item_id FROM crm_kol_item
      WHERE kol_id = #{kolId} AND product_id = #{productId}
      """)
  List<String> listItemIdsByKolProduct(
      @Param("kolId") Long kolId, @Param("productId") Long productId);

  /**
   * Exclusive item IDs of every mapping (current and closed) of the KOL. Used to drop the KOL's
   * stale {@code linkitem} fields before rewriting its current ones; uses {@code
   * idx_crm_kol_item_kol}.
   */
  @Select("SELECT weidian_item_id FROM crm_kol_item WHERE kol_id = #{kolId}")
  List<String> listItemIdsByKol(@Param("kolId") Long kolId);

  /**
   * The subset of exclusive item IDs that still has a current mapping of any KOL (task 11 §4.4).
   * The rebuild's orphan sweep checks each candidate before deleting so a mapping added while the
   * rebuild runs is never dropped; uses {@code idx_crm_kol_item_item_shop_from} on the leading
   * {@code weidian_item_id}. The list must be small (one sweep batch).
   */
  @Select(
      "<script>"
          + "SELECT DISTINCT weidian_item_id FROM crm_kol_item"
          + " WHERE valid_to IS NULL AND weidian_item_id IN"
          + " <foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>"
          + "#{itemId}"
          + "</foreach>"
          + "</script>")
  List<String> filterCurrentItemIds(@Param("itemIds") Collection<String> itemIds);

  /**
   * Owner of each current mapping of these shop items (task 26): {@code weidian_item_id} → KOL id.
   * Uses {@code idx_crm_kol_item_item_shop_from} with the shop prefix.
   */
  @Select(
      "<script>"
          + "SELECT weidian_item_id, kol_id FROM crm_kol_item"
          + " WHERE shop_id = #{shopId} AND valid_to IS NULL AND weidian_item_id IN"
          + " <foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>"
          + "#{itemId}"
          + "</foreach>"
          + "</script>")
  List<KolItemOwnerRow> listCurrentOwners(
      @Param("shopId") Long shopId, @Param("itemIds") Collection<String> itemIds);

  /**
   * The KOL's current mappings with their shop's Weidian id (task 26): the promo-sheet report
   * counts mappings whose shop item did not appear in the sheet.
   */
  @Select(
      """
      SELECT ki.weidian_item_id, s.weidian_shop_id
      FROM crm_kol_item ki
      JOIN crm_shop s ON s.id = ki.shop_id AND s.is_deleted = FALSE
      WHERE ki.kol_id = #{kolId} AND ki.valid_to IS NULL
      """)
  List<KolItemShopRow> listCurrentItemsWithShop(@Param("kolId") Long kolId);

  /** Ends current mappings of a KOL (task 25a idle cleanup via product.api). */
  @Update(
      """
      UPDATE crm_kol_item
         SET valid_to = #{at}, update_by = #{actorId}, update_time = #{at}
       WHERE kol_id = #{kolId} AND valid_to IS NULL
      """)
  int closeCurrentByKol(
      @Param("kolId") Long kolId, @Param("at") Instant at, @Param("actorId") Long actorId);
}
