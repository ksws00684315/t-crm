package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CreatorFundFlowMapper {
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
