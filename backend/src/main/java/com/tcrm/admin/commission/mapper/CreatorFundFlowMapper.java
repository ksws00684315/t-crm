package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.Application;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CreatorFundFlowMapper {
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      SELECT CONCAT('COMMISSION:', w.id) AS id, 'COMMISSION' AS fund_type,
             COALESCE(alloc.amount, payment.amount_cny, w.amount_cny) AS amount,
             w.status, w.application_no, w.create_time AS applied_at
      FROM crm_commission_withdrawal w
      LEFT JOIN crm_commission_withdrawal_payment payment
        ON payment.withdrawal_id = w.id AND payment.tenant_id = w.tenant_id
      CROSS JOIN LATERAL (
        SELECT COUNT(*) AS entries, SUM(a.occupied_cny) FILTER (WHERE
          <choose>
            <when test="shopId != null">o.shop_id = #{shopId}</when>
            <when test="!visible.isEmpty()">
              (o.shop_id IS NULL OR o.shop_id IN
              <foreach collection="visible" item="shop" open="(" separator="," close=")">#{shop}</foreach>)
            </when>
            <otherwise>o.shop_id IS NULL</otherwise>
          </choose>
          <if test="owner != null">AND o.bd_user_id = #{owner}</if>
        ) AS amount
        FROM crm_commission_withdrawal_allocation a
        LEFT JOIN crm_order_item i ON i.id = a.order_item_id AND i.tenant_id = a.tenant_id
        LEFT JOIN crm_commission_item_state s ON s.id = a.item_state_id AND s.tenant_id = a.tenant_id
        LEFT JOIN crm_order o ON o.id = COALESCE(i.order_id, s.order_id) AND o.tenant_id = a.tenant_id
        WHERE a.tenant_id = w.tenant_id AND a.withdrawal_id = w.id
      ) alloc
      WHERE w.tenant_id = #{tenantId} AND w.kol_id = #{creatorId}
        AND w.status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID')
        AND w.create_time &gt;= #{from} AND w.create_time &lt; #{until}
        AND (alloc.amount IS NOT NULL
          <if test="shopId == null and owner == null">OR alloc.entries = 0</if>
        )
      <if test="shopId == null">
      UNION ALL
      SELECT CONCAT('PREPAY:', pr.id), 'PREPAY', COALESCE(p.pay_amount_cny, pr.amount_cny),
             CASE WHEN pr.status = 'APPROVED' THEN 'PENDING_PAYMENT'
                  WHEN pr.status = 'PAID' THEN 'PAID' ELSE 'PENDING_REVIEW' END,
             pr.prepay_no, pr.create_time
      FROM crm_prepay pr
      LEFT JOIN crm_prepay_payment p ON p.prepay_id = pr.id AND p.tenant_id = pr.tenant_id
      WHERE pr.tenant_id = #{tenantId} AND pr.kol_id = #{creatorId}
        AND pr.status IN ('PENDING_APPROVAL', 'PENDING_OPERATIONS_REVIEW',
                          'PENDING_FINANCE_REVIEW', 'APPROVED', 'PAID')
        AND pr.create_time &gt;= #{from} AND pr.create_time &lt; #{until}
      </if>
      ORDER BY applied_at DESC, id DESC
      </script>
      """)
  List<Application> applications(
      @Param("tenantId") long tenantId,
      @Param("creatorId") long creatorId,
      @Param("shopId") Long shopId,
      @Param("owner") Long owner,
      @Param("visible") java.util.Set<Long> visible,
      @Param("from") Instant from,
      @Param("until") Instant until);

  // Read all verified snapshots before applying the display filters: the wallet is shared
  // across shops and dates. Even an older order can consume the available FIFO balance.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT s.id AS state_id, s.order_item_id, s.order_id, o.weidian_order_id AS order_no,
             o.shop_id, o.bd_user_id AS owner_id, o.pay_time, s.accrued_commission_cny AS accrued,
             s.released_commission_cny AS released
      FROM crm_commission_item_state s
      LEFT JOIN crm_order o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
      WHERE s.tenant_id = #{tenantId} AND s.kol_id = #{creatorId}
        AND s.verified AND NOT s.is_deleted
      ORDER BY s.release_at NULLS LAST, s.order_item_id
      """)
  List<Snapshot> snapshots(@Param("tenantId") long tenantId, @Param("creatorId") long creatorId);

  // Group by withdrawal AND order; the payment header is never repeated for each allocation.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT CONCAT(w.id, ':', COALESCE(o.id, i.order_id, s.order_id, 0), ':', MIN(COALESCE(a.id, 0))) AS id,
             COALESCE(o.weidian_order_id, MIN(NULLIF(a.order_no, ''))) AS order_no,
             o.shop_id, o.bd_user_id AS owner_id, p.paid_at AS occurred_at,
             COALESCE(SUM(a.occupied_cny), p.amount_cny) AS amount, w.application_no AS settlement_no
      FROM crm_commission_withdrawal w
      JOIN crm_commission_withdrawal_payment p
        ON p.withdrawal_id = w.id AND p.tenant_id = w.tenant_id
      LEFT JOIN crm_commission_withdrawal_allocation a
        ON a.withdrawal_id = w.id AND a.tenant_id = w.tenant_id
      LEFT JOIN crm_order_item i ON i.id = a.order_item_id AND i.tenant_id = w.tenant_id
      LEFT JOIN crm_commission_item_state s ON s.id = a.item_state_id AND s.tenant_id = w.tenant_id
      LEFT JOIN crm_order o ON o.id = COALESCE(i.order_id, s.order_id) AND o.tenant_id = w.tenant_id
      WHERE w.tenant_id = #{tenantId} AND w.kol_id = #{creatorId}
        AND w.status = 'PAID' AND p.paid_at >= #{from} AND p.paid_at < #{until}
      GROUP BY w.id, w.application_no, COALESCE(o.id, i.order_id, s.order_id, 0),
               o.weidian_order_id, o.shop_id, o.bd_user_id, p.paid_at, p.amount_cny
      """)
  List<Posting> withdrawals(
      @Param("tenantId") long tenantId,
      @Param("creatorId") long creatorId,
      @Param("from") Instant from,
      @Param("until") Instant until);

  // The posting's creator is authoritative even if the item has since been re-attributed.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT CAST(l.id AS varchar) AS id, o.weidian_order_id AS order_no, o.shop_id,
             o.bd_user_id AS owner_id, l.occurred_at, l.amount_cny AS amount,
             CAST(NULL AS varchar) AS settlement_no
      FROM crm_commission_ledger l
      LEFT JOIN crm_commission_item_state s ON s.id = l.source_id AND s.tenant_id = l.tenant_id
      LEFT JOIN crm_order o ON o.id = s.order_id AND o.tenant_id = l.tenant_id
      WHERE l.tenant_id = #{tenantId} AND l.kol_id = #{creatorId}
        AND l.source_type = 'COMMISSION' AND l.entry_type = 'COMMISSION_ADJUSTMENT'
        AND l.amount_cny < 0 AND l.occurred_at >= #{from} AND l.occurred_at < #{until}
      """)
  List<Posting> clawbacks(
      @Param("tenantId") long tenantId,
      @Param("creatorId") long creatorId,
      @Param("from") Instant from,
      @Param("until") Instant until);

  record Snapshot(
      long stateId,
      long orderItemId,
      long orderId,
      String orderNo,
      Long shopId,
      Long ownerId,
      Instant payTime,
      BigDecimal accrued,
      BigDecimal released) {}

  record Posting(
      String id,
      String orderNo,
      Long shopId,
      Long ownerId,
      Instant occurredAt,
      BigDecimal amount,
      String settlementNo) {}
}
