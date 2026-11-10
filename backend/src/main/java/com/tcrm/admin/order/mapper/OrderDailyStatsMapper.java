package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.DailyFactDTO;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Order-owned source for Beijing-day rollups. Every fact join remains in one tenant. */
@Mapper
public interface OrderDailyStatsMapper {
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT DISTINCT (event_time AT TIME ZONE 'Asia/Shanghai')::date
      FROM (
        SELECT pay_time AS event_time FROM crm_order
        WHERE tenant_id = #{tenantId} AND id = #{orderId}
        UNION ALL
        SELECT finish_time FROM crm_refund
        WHERE tenant_id = #{tenantId} AND order_id = #{orderId} AND NOT is_deleted
      ) events
      WHERE event_time IS NOT NULL
      """)
  List<LocalDate> orderDays(@Param("tenantId") long tenantId, @Param("orderId") long orderId);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      WITH changed_orders AS (
        SELECT o.id, o.pay_time
        FROM crm_order o
        WHERE o.tenant_id = #{tenantId} AND o.update_time >= #{from} AND o.update_time < #{until}
        UNION
        SELECT o.id, o.pay_time
        FROM crm_order_item i JOIN crm_order o ON o.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND o.tenant_id = #{tenantId}
          AND i.update_time >= #{from} AND i.update_time < #{until}
      ),
      changed_refunds AS (
        SELECT o.pay_time, r.finish_time
        FROM crm_refund r JOIN crm_order o ON o.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND o.tenant_id = #{tenantId}
          AND r.update_time >= #{from} AND r.update_time < #{until}
        UNION
        SELECT o.pay_time, r.finish_time
        FROM changed_orders o JOIN crm_refund r ON r.order_id = o.id
        WHERE r.tenant_id = #{tenantId}
      ),
      changed_times AS (
        SELECT pay_time AS event_time FROM changed_orders
        UNION SELECT pay_time FROM changed_refunds
        UNION SELECT finish_time FROM changed_refunds
      )
      SELECT DISTINCT (event_time AT TIME ZONE 'Asia/Shanghai')::date
      FROM changed_times WHERE event_time IS NOT NULL
      ORDER BY 1
      """)
  List<LocalDate> changedDays(
      @Param("tenantId") long tenantId, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH scoped_orders AS NOT MATERIALIZED (
        SELECT id, shop_id, COALESCE(kol_id, 0) AS kol_id, COALESCE(bd_user_id, 0) AS owner_id,
               attribution_status, pay_time, pay_amount
        FROM crm_order
        WHERE tenant_id = #{tenantId} AND NOT is_deleted AND pay_time IS NOT NULL
      ),
      paid AS (
        SELECT id, shop_id, kol_id, owner_id, attribution_status, pay_time, pay_amount
        FROM scoped_orders WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      refund_facts AS (
        SELECT p.id AS order_id, p.shop_id, p.kol_id, p.owner_id, p.attribution_status,
               r.order_item_id, r.amount, true AS cohort
        FROM crm_refund r JOIN paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
        UNION ALL
        SELECT o.id, o.shop_id, o.kol_id, o.owner_id, o.attribution_status,
               r.order_item_id, r.amount, false
        FROM crm_refund r JOIN scoped_orders o ON o.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
      ),
      facts AS (
        SELECT 'ORDER' AS grain, shop_id, kol_id, owner_id, '' AS product_code, '' AS sku_code,
               1 AS paid_orders, 0 AS units, pay_amount AS sales,
               0::numeric AS cohort_refunds, 0::numeric AS completed_refunds
        FROM paid
        UNION ALL
        SELECT 'ORDER', p.shop_id, p.kol_id, p.owner_id, '', '', 0, i.quantity, 0, 0, 0
        FROM crm_order_item i JOIN paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted
        UNION ALL
        SELECT 'ITEM', p.shop_id, i.kol_id, p.owner_id, i.weidian_item_id, i.sku_id,
               0, i.quantity, i.pay_amount, 0, 0
        FROM crm_order_item i JOIN paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted AND i.kol_id IS NOT NULL
          AND p.attribution_status = 'ATTRIBUTED'
        UNION ALL
        SELECT 'ORDER', shop_id, kol_id, owner_id, '', '', 0, 0, 0,
               CASE WHEN cohort THEN amount ELSE 0 END,
               CASE WHEN NOT cohort THEN amount ELSE 0 END
        FROM refund_facts
        UNION ALL
        SELECT 'ITEM', r.shop_id, i.kol_id, r.owner_id, i.weidian_item_id, i.sku_id, 0, 0, 0,
               CASE WHEN r.cohort THEN r.amount ELSE 0 END,
               CASE WHEN NOT r.cohort THEN r.amount ELSE 0 END
        FROM refund_facts r JOIN crm_order_item i ON i.id = r.order_item_id AND i.order_id = r.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted AND i.kol_id IS NOT NULL
          AND r.attribution_status = 'ATTRIBUTED'
      )
      SELECT grain, shop_id, kol_id, owner_id, product_code, sku_code,
             SUM(paid_orders) AS paid_orders, SUM(units) AS units, SUM(sales) AS sales,
             SUM(cohort_refunds) AS cohort_refunds, SUM(completed_refunds) AS completed_refunds
      FROM facts GROUP BY grain, shop_id, kol_id, owner_id, product_code, sku_code
      </script>
      """)
  List<DailyFactDTO> rollup(
      @Param("tenantId") long tenantId, @Param("from") Instant from, @Param("until") Instant until);
}
