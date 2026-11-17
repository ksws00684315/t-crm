package com.tcrm.admin.order.mapper;

/** Reporting product identity. Aliases o and i are the paid order and its item. */
final class OrderProductSql {
  private OrderProductSql() {}

  static final String PRODUCT_JOIN =
      """
      LEFT JOIN LATERAL (
        SELECT COALESCE(
          i.product_id,
          (SELECT m.product_id FROM crm_kol_item m
           WHERE m.id = i.kol_item_id AND m.tenant_id = i.tenant_id
             AND m.shop_id = o.shop_id AND m.weidian_item_id = i.weidian_item_id),
          (SELECT MIN(m.product_id) FROM crm_kol_item m
           WHERE m.tenant_id = i.tenant_id AND m.shop_id = o.shop_id
             AND m.weidian_item_id = i.weidian_item_id AND m.product_id IS NOT NULL
             AND m.valid_from &lt;= o.pay_time AND (m.valid_to IS NULL OR m.valid_to &gt; o.pay_time)
           HAVING MIN(m.product_id) = MAX(m.product_id)),
          (SELECT p.id FROM crm_product p
           WHERE p.tenant_id = i.tenant_id AND p.shop_id = o.shop_id
             AND p.weidian_item_id = i.weidian_item_id
           ORDER BY p.is_deleted, p.create_time, p.id LIMIT 1)
        ) AS id
      ) reporting_product ON TRUE
      """;

  static final String FIRST_LINK_JOIN =
      """
      LEFT JOIN crm_product catalog_product ON catalog_product.id = reporting_product.id
        AND catalog_product.tenant_id = i.tenant_id
      LEFT JOIN LATERAL (
        SELECT m.weidian_item_id FROM crm_kol_item m
        WHERE m.tenant_id = i.tenant_id AND m.shop_id = o.shop_id
          AND m.product_id = reporting_product.id
          AND catalog_product.shop_id IS DISTINCT FROM o.shop_id
        ORDER BY m.create_time, m.id LIMIT 1
      ) first_shop_link ON TRUE
      """;

  static final String SPU_ID =
      """
      COALESCE(CASE WHEN catalog_product.shop_id = o.shop_id THEN catalog_product.weidian_item_id END,
        first_shop_link.weidian_item_id,
        (SELECT known.weidian_item_id FROM crm_order_item known
         JOIN crm_order paid ON paid.id = known.order_id AND paid.tenant_id = known.tenant_id
         WHERE reporting_product.id IS NOT NULL AND known.product_id = reporting_product.id
           AND known.tenant_id = i.tenant_id AND paid.shop_id = o.shop_id
           AND NOT known.is_deleted AND NOT paid.is_deleted AND paid.pay_time IS NOT NULL
           AND known.weidian_item_id != ''
         ORDER BY paid.pay_time, known.id LIMIT 1),
        NULLIF(i.weidian_item_id, ''))
      """;

  static final String GROUP_KEY =
      "COALESCE('product:' || reporting_product.id, 'unmapped:' || i.weidian_item_id)";

  // Applies to the projected item with shared reporting_product_id and spu_id columns.
  static final String MATCH =
      """
      (strpos(lower(i.item_name), lower(#{productSearch})) &gt; 0
        OR i.weidian_item_id = #{productSearch}
        OR i.spu_id = #{productSearch}
        OR i.reporting_product_id::text = #{productSearch}
        OR i.spu = #{productSearch}
        OR EXISTS (
          SELECT 1 FROM crm_product p WHERE p.id = i.reporting_product_id AND p.tenant_id = i.tenant_id
            AND (strpos(lower(p.name), lower(#{productSearch})) &gt; 0
              OR lower(p.product_code) = lower(#{productSearch}))
        ))
      """;
}
