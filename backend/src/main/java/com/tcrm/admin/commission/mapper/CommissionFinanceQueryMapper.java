package com.tcrm.admin.commission.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Aggregate money queries for the commission income and overview services (task 62). Company
 * isolation is the tenant plugin's job.
 */
@Mapper
public interface CommissionFinanceQueryMapper {

  /** KOLs with any commission activity: snapshot rows, wallet rows or prepay payments. */
  @Select(
      """
      SELECT DISTINCT kol_id FROM crm_commission_item_state
        WHERE is_deleted = FALSE AND kol_id IS NOT NULL
      UNION SELECT DISTINCT kol_id FROM crm_commission_wallet
      UNION SELECT DISTINCT pr.kol_id FROM crm_prepay_payment pp JOIN crm_prepay pr ON pr.id = pp.prepay_id
      """)
  List<Long> activeKolIds();

  /** Wallet balances of the KOLs; KOLs without a wallet row are absent. */
  @Select(
      "<script>SELECT kol_id, balance_cny FROM crm_commission_wallet WHERE kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + "</script>")
  List<KolMoneyRow> walletBalances(@Param("kolIds") Collection<Long> kolIds);

  /** FIFO recovery amounts already offset against prepay deductions, per KOL (deducted). */
  @Select(
      "<script>SELECT kol_id, COALESCE(SUM(amount_cny), 0) AS amount_cny FROM crm_commission_recovery"
          + " WHERE kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY kol_id</script>")
  List<KolMoneyRow> recoverySums(@Param("kolIds") Collection<Long> kolIds);

  /** Prepay deductions still to be offset (pendingDeduction), per KOL. */
  @Select(
      "<script>SELECT l.kol_id AS kol_id,"
          + " COALESCE(SUM(-l.amount_cny - COALESCE((SELECT SUM(r.amount_cny) FROM crm_commission_recovery r"
          + " WHERE r.deduction_ledger_id = l.id), 0)), 0) AS amount_cny"
          + " FROM crm_commission_ledger l"
          + " WHERE l.entry_type = 'PREPAY_DEDUCT' AND l.kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY l.kol_id</script>")
  List<KolMoneyRow> pendingDeductions(@Param("kolIds") Collection<Long> kolIds);

  /**
   * Cumulative commission already posted to the ledger ({@code COMMISSION_WITHDRAWAL}). Period
   * windows read {@code crm_commission_withdrawal_payment} instead; this sum is only the cumulative
   * paid figure.
   */
  @Select(
      "<script>SELECT kol_id, COALESCE(-SUM(amount_cny), 0) AS amount_cny FROM crm_commission_ledger"
          + " WHERE entry_type = 'COMMISSION_WITHDRAWAL' AND kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY kol_id</script>")
  List<KolMoneyRow> withdrawalPaidSums(@Param("kolIds") Collection<Long> kolIds);

  /** Prepay payments per KOL inside the pay-time window (both bounds optional): prepayPaid. */
  @Select(
      "<script>SELECT pr.kol_id AS kol_id, COALESCE(SUM(pp.pay_amount_cny), 0) AS amount_cny,"
          + " MAX(pp.pay_time) AS last_time"
          + " FROM crm_prepay_payment pp JOIN crm_prepay pr ON pr.id = pp.prepay_id"
          + " WHERE pr.kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " <if test='start != null'>AND pp.pay_time &gt;= #{start}</if>"
          + " <if test='end != null'>AND pp.pay_time &lt; #{end}</if>"
          + " GROUP BY pr.kol_id</script>")
  List<PrepayPaidRow> prepayPaidSums(
      @Param("kolIds") Collection<Long> kolIds,
      @Param("start") Instant start,
      @Param("end") Instant end);

  /**
   * Prepay payment rows for income details and KOL fund records, newest first. Commission
   * withdrawal payments are read from {@code WithdrawalMapper}.
   */
  @Select(
      "<script>SELECT pp.id AS payment_id, pr.id AS source_id, pr.prepay_no AS source_no,"
          + " 'PREPAY' AS payment_type, pr.kol_id AS kol_id, pp.pay_amount_cny AS amount_cny,"
          + " pp.pay_time AS paid_at, pr.payout_account_masked AS masked_recipient,"
          + " pp.voucher_file_id AS voucher_file_id, pr.deduct_from_commission AS deduct,"
          + " pr.status AS source_status, pr.create_time AS applied_at"
          + " FROM crm_prepay_payment pp JOIN crm_prepay pr ON pr.id = pp.prepay_id"
          + " WHERE pr.kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " <if test='start != null'>AND pp.pay_time &gt;= #{start}</if>"
          + " <if test='end != null'>AND pp.pay_time &lt; #{end}</if>"
          + " ORDER BY pp.pay_time DESC, pp.id DESC</script>")
  List<PrepayPaymentRow> prepayPayments(
      @Param("kolIds") Collection<Long> kolIds,
      @Param("start") Instant start,
      @Param("end") Instant end);

  /** Money aggregate of one KOL. */
  record KolMoneyRow(Long kolId, BigDecimal amountCny) {}

  /** Prepay payment sum of one KOL with its latest payment time in the window. */
  record PrepayPaidRow(Long kolId, BigDecimal amountCny, Instant lastTime) {}

  /** One prepay payment row joined with its application. */
  record PrepayPaymentRow(
      Long paymentId,
      Long sourceId,
      String sourceNo,
      String paymentType,
      Long kolId,
      BigDecimal amountCny,
      Instant paidAt,
      String maskedRecipient,
      Long voucherFileId,
      Boolean deduct,
      String sourceStatus,
      Instant appliedAt) {}
}
