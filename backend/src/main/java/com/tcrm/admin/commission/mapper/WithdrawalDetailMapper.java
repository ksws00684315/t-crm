package com.tcrm.admin.commission.mapper;

import com.tcrm.admin.commission.model.WithdrawalDetailRow;
import com.tcrm.admin.commission.model.WithdrawalOrderRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only snapshot storage. Tenant predicates are explicit and also enforced by the plugin. */
@Mapper
public interface WithdrawalDetailMapper {
  @Select(
      "SELECT id, tenant_id, kol_id, detail_json::text AS detail_json, account_cipher FROM crm_withdrawal_detail WHERE id = #{id} AND tenant_id = #{tenantId}")
  WithdrawalDetailRow find(@Param("tenantId") long tenantId, @Param("id") long id);

  @Select(
      "SELECT COUNT(id) FROM crm_withdrawal_order_snapshot WHERE withdrawal_id = #{id} AND tenant_id = #{tenantId}")
  long countOrders(@Param("tenantId") long tenantId, @Param("id") long id);

  @Select(
      "SELECT id, order_no, products_json::text AS products_json, occupied, remaining_snapshot FROM crm_withdrawal_order_snapshot WHERE withdrawal_id = #{id} AND tenant_id = #{tenantId} ORDER BY id ASC LIMIT #{size} OFFSET #{offset}")
  List<WithdrawalOrderRow> orders(
      @Param("tenantId") long tenantId,
      @Param("id") long id,
      @Param("size") int size,
      @Param("offset") long offset);
}
