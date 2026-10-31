package com.tcrm.admin.commission.mapper;

import com.tcrm.admin.commission.model.KolLedgerRow;
import com.tcrm.admin.commission.model.KolPaidPrepayRow;
import com.tcrm.admin.commission.model.PrepayApplicationColumns;
import com.tcrm.admin.commission.model.PrepayDeductionRow;
import com.tcrm.admin.commission.model.PrepayHead;
import com.tcrm.admin.commission.model.PrepayHistoryRow;
import com.tcrm.admin.commission.model.PrepayPageQuery;
import com.tcrm.admin.commission.model.PrepayPaymentRow;
import com.tcrm.admin.commission.model.PrepayRecipientRow;
import com.tcrm.admin.commission.model.PrepayWrite;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Prepay SQL. Company isolation is the tenant plugin's job: statements omit {@code tenant_id}
 * predicates and the insert column, and the plugin adds both. {@code ON CONFLICT} targets still
 * name the column.
 */
@Mapper
public interface PrepayMapper {
  @Select(
      """
      SELECT id FROM crm_prepay
      WHERE kol_id = #{kolId}
        AND status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW',
                       'PENDING_FINANCE_REVIEW', 'APPROVED')
      LIMIT 1
      """)
  Long findActiveId(@Param("kolId") long kolId);

  @Select(
      """
      SELECT id FROM crm_prepay
      WHERE kol_id = #{kolId}
        AND status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW',
                       'PENDING_FINANCE_REVIEW', 'APPROVED')
      ORDER BY create_time DESC
      LIMIT 1
      """)
  Long findLatestActiveId(@Param("kolId") long kolId);

  @Select(
      """
      SELECT id, prepay_no, kol_id, applicant_id, amount_cny, status, create_time
      FROM crm_prepay
      WHERE request_id = #{requestId}
      """)
  PrepayHead findByRequestId(@Param("requestId") UUID requestId);

  @Select(
      """
      SELECT id, prepay_no, kol_id, applicant_id, amount_cny, status, create_time
      FROM crm_prepay
      WHERE id = #{id}
      """)
  PrepayHead findById(@Param("id") long id);

  @Insert(
      """
      INSERT INTO crm_prepay (
        id, kol_id, prepay_no, request_id, applicant_id, amount_cny, status,
        create_by, create_time, update_by, update_time)
      VALUES (
        #{row.id}, #{row.kolId}, #{row.prepayNo}, #{row.requestId}, #{row.applicantId},
        #{row.amount}, 'WAITING_DETAILS', #{row.actorId}, #{row.now}, #{row.actorId}, #{row.now})
      ON CONFLICT (tenant_id, request_id) DO NOTHING
      """)
  int insertWaiting(@Param("row") PrepayWrite.Insert row);

  @Update(
      """
      UPDATE crm_prepay
      SET reason = #{row.reason,jdbcType=VARCHAR},
          expected_pay_date = #{row.expectedPayDate,jdbcType=DATE},
          deduct_from_commission = #{row.deductFromCommission,jdbcType=BOOLEAN},
          payout_account_id = #{row.payoutAccountId,jdbcType=BIGINT},
          payout_account_type = #{row.payoutAccountType,jdbcType=VARCHAR},
          payout_currency = #{row.payoutCurrency,jdbcType=VARCHAR},
          payout_network = #{row.payoutNetwork,jdbcType=VARCHAR},
          payout_holder_name = #{row.payoutHolderName,jdbcType=VARCHAR},
          payout_account_cipher = #{row.payoutAccountCipher,jdbcType=VARCHAR},
          payout_account_masked = #{row.payoutAccountMasked,jdbcType=VARCHAR},
          status = 'PENDING_OPERATIONS_REVIEW',
          update_by = #{row.actorId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = 'WAITING_DETAILS'
      """)
  int completeDetails(@Param("row") PrepayWrite.Details row);

  @Update(
      """
      UPDATE crm_prepay
      SET status = #{row.next},
          approved_by = CASE WHEN #{row.next} = 'APPROVED' THEN #{row.actorId} ELSE approved_by END,
          approved_time = CASE WHEN #{row.next} = 'APPROVED' THEN #{row.now} ELSE approved_time END,
          approval_reason = #{row.reason,jdbcType=VARCHAR},
          update_by = #{row.actorId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = #{row.current}
      """)
  int decide(@Param("row") PrepayWrite.Decision row);

  @Update(
      """
      UPDATE crm_prepay
      SET status = 'CANCELLED',
          cancelled_by = #{row.actorId},
          cancelled_time = #{row.now},
          cancel_reason = #{row.reason,jdbcType=VARCHAR},
          update_by = #{row.actorId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = 'WAITING_DETAILS'
      """)
  int cancel(@Param("row") PrepayWrite.Cancel row);

  @Update(
      """
      UPDATE crm_prepay
      SET status = 'PAID', update_by = #{row.actorId}, update_time = #{row.now}
      WHERE id = #{row.id} AND status = 'APPROVED'
      """)
  int markPaid(@Param("row") PrepayWrite.Paid row);

  @Select("SELECT id FROM crm_prepay WHERE id = #{id} FOR UPDATE")
  Long lockById(@Param("id") long id);

  @Select("SELECT status FROM crm_prepay WHERE id = #{id}")
  String statusOf(@Param("id") long id);

  @Select(
      """
      <script>
      SELECT count(*) FROM crm_prepay
      <where>
        <if test="q.prepayNo != null">AND prepay_no = #{q.prepayNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.kolIds != null">
          AND kol_id IN
          <foreach collection="q.kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      </script>
      """)
  Long count(@Param("q") PrepayPageQuery query);

  @Select(
      """
      <script>
      SELECT id, prepay_no, kol_id, applicant_id, amount_cny, status, create_time
      FROM crm_prepay
      <where>
        <if test="q.prepayNo != null">AND prepay_no = #{q.prepayNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.kolIds != null">
          AND kol_id IN
          <foreach collection="q.kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      ORDER BY create_time DESC, id DESC
      LIMIT #{q.limit} OFFSET #{q.offset}
      </script>
      """)
  List<PrepayHead> page(@Param("q") PrepayPageQuery query);

  @Select(
      """
      SELECT id, reason, expected_pay_date, deduct_from_commission, payout_account_id,
             payout_account_type, payout_currency, payout_network, payout_holder_name,
             payout_account_masked
      FROM crm_prepay
      WHERE id = #{id}
      """)
  PrepayApplicationColumns loadApplication(@Param("id") long id);

  @Select(
      """
      SELECT action, operator_id, operator_name, operator_role, create_time, reason
      FROM crm_prepay_history
      WHERE prepay_id = #{id}
        AND action IN ('OPERATIONS_APPROVED', 'FINANCE_APPROVED', 'APPROVED', 'REJECTED')
      ORDER BY create_time DESC, id DESC
      LIMIT 1
      """)
  PrepayHistoryRow latestApproval(@Param("id") long id);

  @Select(
      """
      SELECT action, operator_id, operator_name, operator_role, create_time, reason
      FROM crm_prepay_history
      WHERE prepay_id = #{id}
      ORDER BY create_time, id
      """)
  List<PrepayHistoryRow> timeline(@Param("id") long id);

  @Select(
      """
      SELECT pay_amount_cny, pay_time, pay_method, pay_remark, voucher_file_id,
             operator_id, operator_name, operator_role
      FROM crm_prepay_payment
      WHERE prepay_id = #{id}
      """)
  PrepayPaymentRow findPayment(@Param("id") long id);

  @Select(
      """
      SELECT p.pay_amount_cny AS posted, l.id AS ledger_id
      FROM crm_prepay_payment p
      JOIN crm_commission_ledger l
        ON l.source_id = p.prepay_id
       AND l.source_type = 'PREPAY'
       AND l.entry_type = 'PREPAY_DEDUCT'
      WHERE p.prepay_id = #{id}
      """)
  PrepayDeductionRow findDeduction(@Param("id") long id);

  @Select(
      """
      SELECT COALESCE(SUM(amount_cny), 0)
      FROM crm_commission_recovery
      WHERE deduction_ledger_id = #{ledgerId}
      """)
  BigDecimal sumRecovered(@Param("ledgerId") long ledgerId);

  @Select(
      """
      SELECT payout_account_type, payout_currency, payout_network, payout_holder_name,
             payout_account_cipher, prepay_no
      FROM crm_prepay
      WHERE id = #{id} AND payout_account_cipher IS NOT NULL
      """)
  PrepayRecipientRow findRecipient(@Param("id") long id);

  @Select("SELECT payout_account_masked FROM crm_prepay WHERE id = #{id}")
  String maskedAccount(@Param("id") long id);

  @Select(
      """
      SELECT p.kol_id
      FROM crm_prepay p
      JOIN crm_prepay_payment payment ON payment.prepay_id = p.id
      WHERE p.id = #{id} AND payment.voucher_file_id = #{fileId} AND p.status = 'PAID'
      """)
  Long paidKolId(@Param("id") long id, @Param("fileId") long fileId);

  @Select("SELECT request_fingerprint FROM crm_prepay_payment WHERE request_id = #{requestId}")
  String findFingerprint(@Param("requestId") UUID requestId);

  /** Allocates the next per-company daily number. No row means the day is exhausted. */
  @Select(
      """
      <script>
      INSERT INTO crm_prepay_sequence (id, sequence_date, last_value)
      VALUES (#{id}, #{day}, 1)
      ON CONFLICT (tenant_id, sequence_date)
      DO UPDATE SET last_value = crm_prepay_sequence.last_value + 1
      WHERE crm_prepay_sequence.last_value &lt; 999999
      RETURNING last_value
      </script>
      """)
  Integer nextSequence(@Param("id") long id, @Param("day") LocalDate day);

  @Insert(
      """
      INSERT INTO crm_prepay_history (
        id, prepay_id, action, operator_id, reason, create_time, operator_name, operator_role)
      VALUES (
        #{row.id}, #{row.prepayId}, #{row.action}, #{row.operatorId},
        #{row.reason,jdbcType=VARCHAR}, #{row.createTime},
        #{row.operatorName,jdbcType=VARCHAR}, #{row.operatorRole})
      """)
  int insertHistory(@Param("row") PrepayWrite.History row);

  @Insert(
      """
      INSERT INTO crm_prepay_outbox (
        id, prepay_id, event_type, payload, attempts, next_attempt_at, create_time)
      VALUES (
        #{row.id}, #{row.prepayId}, #{row.eventType}, CAST(#{row.payload} AS jsonb),
        0, #{row.nextAttemptAt}, #{row.createTime})
      """)
  int insertOutbox(@Param("row") PrepayWrite.Outbox row);

  @Insert(
      """
      INSERT INTO crm_prepay_payment (
        id, prepay_id, request_id, request_fingerprint, pay_amount_cny, pay_time, pay_method,
        pay_remark, voucher_file_id, operator_id, create_time, operator_name, operator_role)
      VALUES (
        #{row.id}, #{row.prepayId}, #{row.requestId}, #{row.fingerprint}, #{row.amount},
        #{row.payTime}, #{row.method}, #{row.remark,jdbcType=VARCHAR}, #{row.voucherFileId},
        #{row.operatorId}, #{row.createTime}, #{row.operatorName,jdbcType=VARCHAR},
        #{row.operatorRole})
      """)
  int insertPayment(@Param("row") PrepayWrite.Payment row);

  @Select(
      """
      SELECT count(*) FROM crm_prepay
      WHERE kol_id = #{kolId} AND status = 'PAID'
      """)
  Long countPaid(@Param("kolId") long kolId);

  @Select(
      """
      SELECT id FROM crm_prepay
      WHERE kol_id = #{kolId} AND status = 'PAID'
      ORDER BY create_time DESC, id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Long> listPaidIds(
      @Param("kolId") long kolId, @Param("limit") int limit, @Param("offset") long offset);

  @Select(
      """
      SELECT p.id, p.prepay_no, p.deduct_from_commission, payment.pay_amount_cny, payment.pay_time,
             payment.pay_method, payment.voucher_file_id, wallet.balance_cny, l.id AS deduction_id
      FROM crm_prepay p
      JOIN crm_prepay_payment payment ON payment.prepay_id = p.id
      LEFT JOIN crm_commission_wallet wallet ON wallet.kol_id = p.kol_id
      LEFT JOIN crm_commission_ledger l
        ON l.source_type = 'PREPAY' AND l.source_id = p.id AND l.entry_type = 'PREPAY_DEDUCT'
      WHERE p.kol_id = #{kolId} AND p.id = #{id} AND p.status = 'PAID'
      """)
  KolPaidPrepayRow findPaid(@Param("kolId") long kolId, @Param("id") long id);

  @Select(
      """
      SELECT count(*) FROM crm_prepay
      WHERE kol_id = #{kolId} AND id = #{id} AND status = 'PAID'
      """)
  Long countPaidOne(@Param("kolId") long kolId, @Param("id") long id);

  @Select(
      """
      SELECT count(*)
      FROM crm_prepay p
      JOIN crm_prepay_payment payment ON payment.prepay_id = p.id
      WHERE p.kol_id = #{kolId} AND p.status = 'PAID'
      """)
  Long countPaidPayments(@Param("kolId") long kolId);

  @Select(
      """
      SELECT count(*)
      FROM crm_prepay p
      JOIN crm_commission_ledger l
        ON l.source_type = 'PREPAY' AND l.source_id = p.id AND l.entry_type = 'PREPAY_DEDUCT'
      WHERE p.kol_id = #{kolId} AND p.status = 'PAID'
      """)
  Long countPaidDeductions(@Param("kolId") long kolId);

  @Select(
      """
      SELECT payment.id AS entry_id, p.id AS prepay_id, p.prepay_no,
             'PREPAY_PAID' AS entry_type, payment.pay_amount_cny AS amount_cny,
             payment.pay_time AS occurred_at, NULL::numeric AS balance_after_cny
      FROM crm_prepay p
      JOIN crm_prepay_payment payment ON payment.prepay_id = p.id
      WHERE p.kol_id = #{kolId} AND p.status = 'PAID'
      UNION ALL
      SELECT l.id AS entry_id, p.id AS prepay_id, p.prepay_no,
             'PREPAY_DEDUCT' AS entry_type, l.amount_cny, l.occurred_at, l.balance_after_cny
      FROM crm_prepay p
      JOIN crm_commission_ledger l
        ON l.source_type = 'PREPAY' AND l.source_id = p.id AND l.entry_type = 'PREPAY_DEDUCT'
      WHERE p.kol_id = #{kolId} AND p.status = 'PAID'
      ORDER BY occurred_at DESC, entry_id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<KolLedgerRow> listLedger(
      @Param("kolId") long kolId, @Param("limit") int limit, @Param("offset") long offset);
}
