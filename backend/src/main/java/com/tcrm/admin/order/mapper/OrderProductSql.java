package com.tcrm.admin.order.mapper;

/** Reporting product identity. Aliases o and i are the paid order and its item. */
final class OrderProductSql {
  private OrderProductSql() {}

  static final String PRODUCT_JOIN =
      """
      LEFT JOIN LATERAL (
        SELECT MIN(candidate.id) AS id
        FROM (
          SELECT i.product_id AS id
          UNION ALL
          SELECT m.product_id FROM crm_kol_item m
          WHERE m.tenant_id = i.tenant_id AND m.shop_id = o.shop_id
            AND m.weidian_item_id = i.weidian_item_id
            AND (m.id = i.kol_item_id OR
                 (m.valid_from &lt;= o.pay_time AND (m.valid_to IS NULL OR m.valid_to &gt; o.pay_time)))
          UNION ALL
          SELECT p.id FROM crm_product p
          WHERE p.tenant_id = i.tenant_id AND p.shop_id = o.shop_id
            AND p.weidian_item_id = i.weidian_item_id
            AND NOT p.is_deleted
        ) candidate
        JOIN crm_product source_product ON source_product.id = candidate.id
          AND source_product.tenant_id = i.tenant_id
        HAVING COUNT(DISTINCT candidate.id) = 1
      ) reporting_product ON TRUE
      """;

  static final String CATALOG_JOIN =
      """
      LEFT JOIN crm_product catalog_product ON catalog_product.id = reporting_product.id
        AND catalog_product.tenant_id = i.tenant_id
      """;

  static final String SPU_ID = "NULLIF(catalog_product.weidian_item_id, '')";

  static final String GROUP_KEY =
      "COALESCE('product:' || reporting_product.id, 'unmapped:' || o.shop_id || ':' || i.weidian_item_id)";

  // Applies to the projected item with shared reporting_product_id and spu_id columns.
  static final String MATCH =
      """
      (strpos(lower(i.item_name), lower(#{productSearch})) &gt; 0
        OR i.weidian_item_id = #{productSearch}
        OR i.spu_id = #{productSearch}
        OR i.spu = #{productSearch}
        OR EXISTS (
          SELECT 1 FROM crm_product p WHERE p.id = i.reporting_product_id AND p.tenant_id = i.tenant_id
            AND (strpos(lower(p.name), lower(#{productSearch})) &gt; 0
              OR lower(p.product_code) = lower(#{productSearch}))
        ))
      """;
}
