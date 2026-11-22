package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.commission.model.FinanceFlowDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code crm_finance_flow} (task 159): append-only finance flow entries, queried per order. */
@Mapper
public interface FinanceFlowMapper extends BaseMapper<FinanceFlowDO> {

  @Select(
      """
      SELECT id, tenant_id, order_id, order_item_id, kol_id, subject, amount_cny, occurred_at,
             source_type, source_id, source_key, flow_no, evidence, create_time, update_time
      FROM crm_finance_flow
      WHERE tenant_id = #{tenantId} AND source_key = #{sourceKey} AND is_deleted = FALSE
      LIMIT 1
      """)
  FinanceFlowDO findBySourceKey(
      @Param("tenantId") long tenantId, @Param("sourceKey") String sourceKey);

  @Select(
      """
      SELECT id, tenant_id, order_id, order_item_id, kol_id, subject, amount_cny, occurred_at,
             source_type, source_id, source_key, flow_no, evidence, create_time, update_time
      FROM crm_finance_flow
      WHERE tenant_id = #{tenantId} AND order_id = #{orderId} AND is_deleted = FALSE
      ORDER BY occurred_at, id
      """)
  List<FinanceFlowDO> selectByOrder(
      @Param("tenantId") long tenantId, @Param("orderId") long orderId);

  /** Provisional after-sales holds and their restorations of one line (task 158 §2.1). */
  @Select(
      """
      SELECT id, tenant_id, order_id, order_item_id, kol_id, subject, amount_cny, occurred_at,
             source_type, source_id, source_key, flow_no, evidence, create_time, update_time
      FROM crm_finance_flow
      WHERE tenant_id = #{tenantId} AND order_item_id = #{orderItemId}
        AND subject = 'COMMISSION_CLAWBACK' AND evidence IN ('PROVISIONAL', 'RESTORE')
        AND is_deleted = FALSE
      """)
  List<FinanceFlowDO> selectClawbackProvisional(
      @Param("tenantId") long tenantId, @Param("orderItemId") long orderItemId);
}
