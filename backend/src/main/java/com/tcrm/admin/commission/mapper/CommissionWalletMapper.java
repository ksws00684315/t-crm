package com.tcrm.admin.commission.mapper;

import com.tcrm.admin.commission.model.LedgerSourceRow;
import com.tcrm.admin.commission.model.OutstandingDeduction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Wallet, ledger and recovery SQL. Company isolation is the tenant plugin's job. The outstanding
 * query is a real LEFT JOIN, so the plugin keeps the unpaid side when the right table has no row.
 */
@Mapper
public interface CommissionWalletMapper {
  @Insert(
      """
      INSERT INTO crm_commission_wallet (id, kol_id, balance_cny, create_time, update_time)
      VALUES (#{id}, #{kolId}, 0, #{now}, #{now})
      ON CONFLICT (tenant_id, kol_id) DO NOTHING
      """)
  int insertIgnore(@Param("id") long id, @Param("kolId") long kolId, @Param("now") Instant now);

  @Select(
      """
      SELECT balance_cny FROM crm_commission_wallet WHERE kol_id = #{kolId} FOR UPDATE
      """)
  BigDecimal lockBalance(@Param("kolId") long kolId);

  @Select("SELECT balance_cny FROM crm_commission_wallet WHERE kol_id = #{kolId}")
  BigDecimal findBalance(@Param("kolId") long kolId);

  @Select(
      """
      SELECT amount_cny, balance_after_cny, kol_id, source_type, source_id
      FROM crm_commission_ledger
      WHERE source_key = #{sourceKey}
      """)
  LedgerSourceRow findBySourceKey(@Param("sourceKey") String sourceKey);

  @Insert(
      """
      INSERT INTO crm_commission_ledger (
        id, kol_id, entry_type, amount_cny, source_type, source_id, source_key,
        occurred_at, balance_after_cny, create_by, create_time)
      VALUES (
        #{id}, #{kolId}, #{entryType}, #{amount}, #{sourceType}, #{sourceId}, #{sourceKey},
        #{occurredAt}, #{balanceAfter}, #{createBy,jdbcType=BIGINT}, #{createTime})
      """)
  int insertLedger(
      @Param("id") long id,
      @Param("kolId") long kolId,
      @Param("entryType") String entryType,
      @Param("amount") BigDecimal amount,
      @Param("sourceType") String sourceType,
      @Param("sourceId") long sourceId,
      @Param("sourceKey") String sourceKey,
      @Param("occurredAt") Instant occurredAt,
      @Param("balanceAfter") BigDecimal balanceAfter,
      @Param("createBy") Long createBy,
      @Param("createTime") Instant createTime);

  @Update(
      """
      UPDATE crm_commission_wallet
      SET balance_cny = #{balance}, update_time = #{now}
      WHERE kol_id = #{kolId}
      """)
  int updateBalance(
      @Param("kolId") long kolId, @Param("balance") BigDecimal balance, @Param("now") Instant now);

  @Select(
      """
      SELECT l.id AS ledger_id,
             -l.amount_cny - COALESCE(SUM(r.amount_cny), 0) AS remaining
      FROM crm_commission_ledger l
      LEFT JOIN crm_commission_recovery r ON r.deduction_ledger_id = l.id
      WHERE l.kol_id = #{kolId} AND l.entry_type = 'PREPAY_DEDUCT'
      GROUP BY l.id, l.amount_cny, l.occurred_at
      HAVING -l.amount_cny > COALESCE(SUM(r.amount_cny), 0)
      ORDER BY l.occurred_at, l.id
      """)
  List<OutstandingDeduction> listOutstanding(@Param("kolId") long kolId);

  @Insert(
      """
      INSERT INTO crm_commission_recovery (
        id, kol_id, credit_ledger_id, deduction_ledger_id, amount_cny, create_time)
      VALUES (
        #{id}, #{kolId}, #{creditLedgerId}, #{deductionLedgerId}, #{amount}, #{createTime})
      """)
  int insertRecovery(
      @Param("id") long id,
      @Param("kolId") long kolId,
      @Param("creditLedgerId") long creditLedgerId,
      @Param("deductionLedgerId") long deductionLedgerId,
      @Param("amount") BigDecimal amount,
      @Param("createTime") Instant createTime);
}
