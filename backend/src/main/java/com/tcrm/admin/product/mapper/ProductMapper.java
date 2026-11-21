package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.api.ProductAccessApi.RankingInventory;
import com.tcrm.admin.product.api.ProductAccessApi.RankingInventoryScope;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.PromoProductRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<ProductDO> {
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH visible_shops AS (
        SELECT id, status FROM crm_shop WHERE tenant_id = #{s.tenantId} AND NOT is_deleted
        AND id IN
        <foreach collection="s.shopIds" item="shop" open="(" separator="," close=")">#{shop}</foreach>
      ), links AS (
        SELECT m.product_id, m.shop_id, m.kol_id, m.weidian_item_id, m.item_name, shop.status
        FROM crm_kol_item m
        JOIN visible_shops shop ON shop.id = m.shop_id
        JOIN crm_kol k ON k.id = m.kol_id AND k.tenant_id = m.tenant_id AND NOT k.is_deleted
        WHERE m.tenant_id = #{s.tenantId} AND m.product_id IS NOT NULL
          AND m.valid_from &lt;= CURRENT_TIMESTAMP
          AND (m.valid_to IS NULL OR m.valid_to &gt; CURRENT_TIMESTAMP)
        <if test="s.ownerId != null">AND k.bd_user_id = #{s.ownerId}</if>
        <if test="s.creatorIds != null">
          <choose>
            <when test="s.creatorIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND k.id IN <foreach collection="s.creatorIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </otherwise>
          </choose>
        </if>
        <if test="s.inviterIds != null">
          <choose>
            <when test="s.inviterIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND k.bd_user_id IN
              <foreach collection="s.inviterIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </otherwise>
          </choose>
        </if>
      ), eligible AS (
        SELECT p.id FROM crm_product p
        WHERE p.tenant_id = #{s.tenantId} AND NOT p.is_deleted
        AND (
          <if test="s.ownerId == null and s.creatorIds == null and s.inviterIds == null">
            EXISTS (SELECT 1 FROM visible_shops v WHERE v.id = p.shop_id) OR
          </if>
          EXISTS (SELECT 1 FROM links l WHERE l.product_id = p.id)
        )
        <if test="s.keyword != null and !s.keyword.isBlank()">
          AND (p.weidian_item_id = #{s.keyword} OR lower(p.product_code) = lower(#{s.keyword})
            OR strpos(lower(p.name), lower(#{s.keyword})) &gt; 0
            OR EXISTS (SELECT 1 FROM links l WHERE l.product_id = p.id
              AND (l.weidian_item_id = #{s.keyword}
                OR strpos(lower(l.item_name), lower(#{s.keyword})) &gt; 0)))
        </if>
      )
      SELECT NULL::bigint AS product_id, COUNT(*) AS total_spu_count,
             0::bigint AS on_sale_shop_count, 0::bigint AS linked_creator_count
      FROM eligible
      <if test="!s.rankedProductIds.isEmpty()">
        UNION ALL
        SELECT p.id, 0::bigint,
               COUNT(DISTINCT l.shop_id) FILTER (WHERE l.status = 1)
                 + CASE WHEN source.status = 1 AND p.status = 1
                    AND COUNT(*) FILTER (WHERE l.shop_id = p.shop_id AND l.status = 1) = 0
                   THEN 1 ELSE 0 END,
               COUNT(DISTINCT l.kol_id)
        FROM crm_product p
        LEFT JOIN visible_shops source ON source.id = p.shop_id
        LEFT JOIN links l ON l.product_id = p.id
        WHERE p.tenant_id = #{s.tenantId} AND NOT p.is_deleted AND p.id IN
        <foreach collection="s.rankedProductIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY p.id, source.status
      </if>
      </script>
      """)
  List<RankingInventory> rankingInventory(@Param("s") RankingInventoryScope scope);

  @Select("SELECT nextval('crm_product_code_seq')")
  long nextCodeNumber();

  /** True when the code is taken by any row, including logically deleted ones (never reused). */
  @Select("SELECT EXISTS (SELECT 1 FROM crm_product WHERE product_code = #{code})")
  boolean existsCode(@Param("code") String code);

  /**
   * True when an undeleted original product already uses this shop + weidian item id (exclusive
   * items must not collide with original products).
   */
  @Select(
      """
      SELECT EXISTS (
        SELECT 1 FROM crm_product
        WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND is_deleted = FALSE
      )
      """)
  boolean existsAliveShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /** Original products of these shop items, keyed by {@code weidianItemId} (task 26). */
  @Select(
      "<script>"
          + "SELECT weidian_item_id, id AS product_id, product_code FROM crm_product"
          + " WHERE shop_id = #{shopId} AND is_deleted = FALSE AND weidian_item_id IN"
          + " <foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>"
          + "#{itemId}"
          + "</foreach>"
          + "</script>")
  List<PromoProductRow> listByShopItems(
      @Param("shopId") Long shopId, @Param("itemIds") Collection<String> itemIds);

  /**
   * Serializes original-product and exclusive-item writes for one shop item until the surrounding
   * transaction ends. The key comes from {@code ProductRules.shopItemLockKey}.
   */
  @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
  Integer lockShopItem(@Param("key") String key);

  /** Id of the soft-deleted product row for this shop item, if any (its code stays occupied). */
  @Select(
      """
      SELECT id FROM crm_product
      WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND is_deleted = TRUE
      ORDER BY id DESC
      LIMIT 1
      """)
  Long findDeletedShopItemId(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /** Revives a soft-deleted product in place, keeping its code; image only set when provided. */
  @Update(
      """
      UPDATE crm_product
      SET is_deleted = FALSE,
          name = #{name},
          price = #{price},
          image_url = COALESCE(#{imageUrl, jdbcType=VARCHAR}, image_url),
          update_time = #{updateTime}
      WHERE id = #{id} AND is_deleted = TRUE
      """)
  int reviveDeletedShopItem(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("price") BigDecimal price,
      @Param("imageUrl") String imageUrl,
      @Param("updateTime") Instant updateTime);
}
