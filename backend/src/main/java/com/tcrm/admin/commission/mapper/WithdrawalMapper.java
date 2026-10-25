package com.tcrm.admin.commission.mapper;

import com.tcrm.admin.commission.model.FinanceTaskQuery;
import com.tcrm.admin.commission.model.FinanceTaskRow;
import com.tcrm.admin.commission.model.WithdrawalPageQuery;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Withdrawal SQL. Company isolation is the tenant plugin's job: statements omit {@code tenant_id}
 * predicates and the insert column, and the plugin adds both. {@code ON CONFLICT} targets still
 * name the column.
 */
@Mapper
public interface WithdrawalMapper {
  @Select(
      """
      SELECT id FROM crm_commission_withdrawal
      WHERE kol_id = #{kolId} AND request_id = #{requestId}
      """)
  Long findIdByRequest(@Param("kolId") long kolId, @Param("requestId") UUID requestId);

  @Select(
      """
      SELECT id, kol_id, application_no, amount_cny, status, actor_id, create_time
      FROM crm_commission_withdrawal
      WHERE id = #{id}
      """)
  WithdrawalRow.Head findHead(@Param("id") long id);

  @Select(
      """
      SELECT id, kol_id, application_no, request_id, request_fingerprint, previous_id, amount_cny,
             status, paused, pause_reason, account_id, account_type, account_cipher,
             account_masked, account_currency, account_network, account_holder,
             account_qr_file_id, note, review_note, finance_note, reviewer_id, reviewed_at,
             actor_id, create_time
      FROM crm_commission_withdrawal
      WHERE id = #{id}
      """)
  WithdrawalRow findRow(@Param("id") long id);

  @Select("SELECT id FROM crm_commission_withdrawal WHERE id = #{id} FOR UPDATE")
  Long lockById(@Param("id") long id);

  @Select("SELECT status FROM crm_commission_withdrawal WHERE id = #{id}")
  String statusOf(@Param("id") long id);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal (
        id, kol_id, application_no, request_id, request_fingerprint, previous_id, amount_cny,
        status, account_id, account_type, account_cipher, account_masked, account_currency,
        account_network, account_holder, account_qr_file_id, note, actor_id, create_time,
        update_by, update_time)
      VALUES (
        #{row.id}, #{row.kolId}, #{row.applicationNo}, #{row.requestId}, #{row.fingerprint},
        #{row.previousId,jdbcType=BIGINT}, #{row.amount}, 'PENDING_REVIEW',
        #{row.accountId,jdbcType=BIGINT}, #{row.accountType}, #{row.accountCipher},
        #{row.accountMasked}, #{row.accountCurrency,jdbcType=VARCHAR},
        #{row.accountNetwork,jdbcType=VARCHAR}, #{row.accountHolder},
        #{row.accountQrFileId,jdbcType=BIGINT}, #{row.note,jdbcType=VARCHAR}, #{row.actorId},
        #{row.now}, #{row.actorId}, #{row.now})
      ON CONFLICT (tenant_id, kol_id, request_id) DO NOTHING
      """)
  int insert(@Param("row") WithdrawalWrite.Insert row);

  /** Review decision: approve moves to PENDING_PAYMENT, reject to REJECTED. */
  @Update(
      """
      UPDATE crm_commission_withdrawal
      SET status = #{row.next},
          review_note = #{row.reviewNote,jdbcType=VARCHAR},
          reviewer_id = #{row.reviewerId},
          reviewed_at = #{row.now},
          update_by = #{row.reviewerId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = #{row.current}
      """)
  int decide(@Param("row") WithdrawalWrite.Decide row);

  /** Creator withdrawal before the review; releases the occupation by leaving the active set. */
  @Update(
      """
      UPDATE crm_commission_withdrawal
      SET status = 'WITHDRAWN',
          update_by = #{row.kolId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = 'PENDING_REVIEW' AND kol_id = #{row.kolId}
      """)
  int withdraw(@Param("row") WithdrawalWrite.Withdraw row);

  @Update(
      """
      UPDATE crm_commission_withdrawal
      SET status = 'PAID',
          finance_note = #{row.financeNote,jdbcType=VARCHAR},
          update_by = #{row.operatorId},
          update_time = #{row.now}
      WHERE id = #{row.id} AND status = 'PENDING_PAYMENT'
      """)
  int markPaid(@Param("row") WithdrawalWrite.Paid row);

  @Select(
      """
      <script>
      SELECT id, kol_id, application_no, amount_cny, status, actor_id, create_time
      FROM crm_commission_withdrawal
      <where>
        <if test="q.applicationNo != null">AND application_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.kolIds != null">
          AND kol_id IN
          <foreach collection="q.kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        <if test="q.bdIds != null">
          AND kol_id IN
          <foreach collection="q.bdIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        <if test="q.ownIds != null">
          AND kol_id IN
          <foreach collection="q.ownIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      ORDER BY create_time DESC, id DESC
      LIMIT #{q.limit} OFFSET #{q.offset}
      </script>
      """)
  List<WithdrawalRow.Head> page(@Param("q") WithdrawalPageQuery query);

  @Select(
      """
      <script>
      SELECT count(*) FROM crm_commission_withdrawal
      <where>
        <if test="q.applicationNo != null">AND application_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.kolIds != null">
          AND kol_id IN
          <foreach collection="q.kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        <if test="q.bdIds != null">
          AND kol_id IN
          <foreach collection="q.bdIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        <if test="q.ownIds != null">
          AND kol_id IN
          <foreach collection="q.ownIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      </script>
      """)
  Long count(@Param("q") WithdrawalPageQuery query);

  /** Money still occupied by this creator's in-flight applications. */
  @Select(
      """
      SELECT COALESCE(SUM(amount_cny), 0)
      FROM crm_commission_withdrawal
      WHERE kol_id = #{kolId} AND status IN ('PENDING_REVIEW', 'PENDING_PAYMENT')
      """)
  BigDecimal sumActiveOccupied(@Param("kolId") long kolId);

  /**
   * In-flight occupation per KOL ({@code PENDING_REVIEW} and {@code PENDING_PAYMENT} only). Paid
   * applications are not occupied.
   */
  @Select(
      """
      <script>
      SELECT kol_id, COALESCE(SUM(amount_cny), 0) AS amount_cny
      FROM crm_commission_withdrawal
      WHERE status IN ('PENDING_REVIEW', 'PENDING_PAYMENT')
        AND kol_id IN
      <foreach collection="kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY kol_id
      </script>
      """)
  List<KolAmountRow> sumActiveOccupiedByKol(@Param("kolIds") Collection<Long> kolIds);

  /**
   * Commission paid inside the payment window (inclusive start, exclusive end; null bound is open).
   * {@code PAID} only. This is the period figure; the ledger sum stays cumulative.
   */
  @Select(
      """
      <script>
      SELECT w.kol_id AS kol_id, COALESCE(SUM(p.amount_cny), 0) AS amount_cny,
             MAX(p.paid_at) AS last_time
      FROM crm_commission_withdrawal_payment p
      JOIN crm_commission_withdrawal w ON w.id = p.withdrawal_id
      WHERE w.status = 'PAID'
        AND w.kol_id IN
      <foreach collection="kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      <if test="start != null">AND p.paid_at &gt;= #{start}</if>
      <if test="end != null">AND p.paid_at &lt; #{end}</if>
      GROUP BY w.kol_id
      </script>
      """)
  List<PaidWindowRow> sumPaidInWindow(
      @Param("kolIds") Collection<Long> kolIds,
      @Param("start") Instant start,
      @Param("end") Instant end);

  /** Paid withdrawal payments in the same window, newest payment first. */
  @Select(
      """
      <script>
      SELECT p.id AS payment_id, w.id AS withdrawal_id, w.application_no AS application_no,
             w.kol_id AS kol_id, p.amount_cny AS amount_cny, p.actual_amount AS actual_amount,
             p.actual_currency AS actual_currency, p.paid_at AS paid_at,
             p.voucher_file_id AS voucher_file_id
      FROM crm_commission_withdrawal_payment p
      JOIN crm_commission_withdrawal w ON w.id = p.withdrawal_id
      WHERE w.status = 'PAID'
        AND w.kol_id IN
      <foreach collection="kolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      <if test="start != null">AND p.paid_at &gt;= #{start}</if>
      <if test="end != null">AND p.paid_at &lt; #{end}</if>
      ORDER BY p.paid_at DESC, p.id DESC
      </script>
      """)
  List<PaidPaymentRow> listPaidPayments(
      @Param("kolIds") Collection<Long> kolIds,
      @Param("start") Instant start,
      @Param("end") Instant end);

  /** Sum paid = wallet money that left through withdrawals. */
  @Select(
      """
      SELECT COALESCE(SUM(amount_cny), 0)
      FROM crm_commission_withdrawal
      WHERE kol_id = #{kolId} AND status = 'PAID'
      """)
  BigDecimal sumPaid(@Param("kolId") long kolId);

  /** Funding rows already occupied per order item, in FIFO funding status. */
  @Select(
      """
      <script>
      SELECT a.order_item_id AS order_item_id, COALESCE(SUM(a.occupied_cny), 0) AS occupied_cny
      FROM crm_commission_withdrawal_allocation a
      JOIN crm_commission_withdrawal w ON w.id = a.withdrawal_id
      WHERE w.kol_id = #{kolId} AND w.status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID')
        AND a.order_item_id IN
      <foreach collection="orderItemIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY a.order_item_id
      </script>
      """)
  List<ItemOccupation> sumOccupiedByItem(
      @Param("kolId") long kolId, @Param("orderItemIds") List<Long> orderItemIds);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_allocation (
        id, withdrawal_id, order_item_id, item_state_id, order_no, item_name, sku_name, image_url,
        occupied_cny, remaining_snapshot_cny, source_released_cny, create_time)
      VALUES (
        #{row.id}, #{row.withdrawalId}, #{row.orderItemId}, #{row.itemStateId,jdbcType=BIGINT},
        #{row.orderNo}, #{row.itemName}, #{row.skuName,jdbcType=VARCHAR},
        #{row.imageUrl,jdbcType=VARCHAR}, #{row.occupied},
        #{row.remainingSnapshot}, #{row.sourceReleased}, #{row.createTime})
      """)
  int insertAllocation(@Param("row") WithdrawalWrite.Allocation row);

  @Select(
      """
      SELECT id, order_item_id, order_no, item_name, sku_name, image_url, occupied_cny,
             remaining_snapshot_cny, source_released_cny
      FROM crm_commission_withdrawal_allocation
      WHERE withdrawal_id = #{withdrawalId}
      ORDER BY id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<WithdrawalRows.AllocationRow> pageAllocations(
      @Param("withdrawalId") long withdrawalId,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("SELECT count(*) FROM crm_commission_withdrawal_allocation WHERE withdrawal_id = #{id}")
  Long countAllocations(@Param("id") long id);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_order (
        id, withdrawal_id, order_id, order_no, occupied_cny, remaining_snapshot_cny, create_time)
      VALUES (
        #{row.id}, #{row.withdrawalId}, #{row.orderId}, #{row.orderNo}, #{row.occupied},
        #{row.remainingSnapshot,jdbcType=NUMERIC}, #{row.createTime})
      """)
  int insertOrderSnapshot(@Param("row") WithdrawalWrite.OrderSnapshot row);

  /** Order-level snapshot pagination (13.04): one row per order, id ascending. */
  @Select(
      """
      SELECT id, order_id, order_no, occupied_cny, remaining_snapshot_cny
      FROM crm_commission_withdrawal_order
      WHERE withdrawal_id = #{id}
      ORDER BY id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<OrderSnapshotRow> pageOrderSnapshots(
      @Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

  @Select("SELECT count(*) FROM crm_commission_withdrawal_order WHERE withdrawal_id = #{id}")
  Long countOrderSnapshots(@Param("id") long id);

  /** Row-level allocations of one order snapshot, for the products[] of that order row. */
  @Select(
      """
      SELECT id, order_item_id, order_no, item_name, sku_name, image_url, occupied_cny,
             remaining_snapshot_cny, source_released_cny
      FROM crm_commission_withdrawal_allocation
      WHERE withdrawal_id = #{withdrawalId} AND order_no = #{orderNo}
      ORDER BY id
      """)
  List<WithdrawalRows.AllocationRow> allocationsOfOrder(
      @Param("withdrawalId") long withdrawalId, @Param("orderNo") String orderNo);

  @Select(
      """
      SELECT COALESCE(SUM(occupied_cny), 0)
      FROM crm_commission_withdrawal_allocation
      WHERE withdrawal_id = #{id}
      """)
  BigDecimal sumAllocated(@Param("id") long id);

  /** Withdrawal occupations of order items (order chain, 09.04); called per company scope. */
  @Select(
      """
      <script>
      SELECT a.order_item_id AS order_item_id, w.id AS withdrawal_id,
             w.application_no AS application_no, w.status AS status,
             a.occupied_cny AS occupied_cny, a.source_released_cny AS source_released_cny,
             a.remaining_snapshot_cny AS remaining_snapshot_cny,
             w.create_time AS submitted_at, p.paid_at AS paid_at
      FROM crm_commission_withdrawal_allocation a
      JOIN crm_commission_withdrawal w ON w.id = a.withdrawal_id
      LEFT JOIN crm_commission_withdrawal_payment p ON p.withdrawal_id = w.id
      WHERE w.status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID')
        AND a.order_item_id IN
      <foreach collection="orderItemIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY a.order_item_id, w.create_time, w.id
      </script>
      """)
  List<OccupationRow> listOccupationsByOrderItems(@Param("orderItemIds") List<Long> orderItemIds);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_history (
        id, withdrawal_id, action, actor_id, actor_name, actor_role, note, create_time)
      VALUES (
        #{row.id}, #{row.withdrawalId}, #{row.action}, #{row.actorId,jdbcType=BIGINT},
        #{row.actorName,jdbcType=VARCHAR}, #{row.actorRole}, #{row.note,jdbcType=VARCHAR},
        #{row.createTime})
      """)
  int insertHistory(@Param("row") WithdrawalWrite.History row);

  @Select(
      """
      SELECT action, actor_id, actor_name, actor_role, note, create_time
      FROM crm_commission_withdrawal_history
      WHERE withdrawal_id = #{id}
      ORDER BY create_time, id
      """)
  List<WithdrawalRows.HistoryRow> timeline(@Param("id") long id);

  @Select(
      "SELECT request_fingerprint FROM crm_commission_withdrawal_payment WHERE request_id = #{requestId}")
  String findPaymentFingerprint(@Param("requestId") UUID requestId);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_payment (
        id, withdrawal_id, request_id, request_fingerprint, amount_cny, actual_amount,
        actual_currency, paid_at, method, finance_note, conversion_note, voucher_file_id,
        operator_id, operator_name, operator_role, create_time)
      VALUES (
        #{row.id}, #{row.withdrawalId}, #{row.requestId}, #{row.fingerprint}, #{row.amount},
        #{row.actualAmount}, #{row.actualCurrency}, #{row.paidAt}, #{row.method},
        #{row.financeNote,jdbcType=VARCHAR}, #{row.conversionNote,jdbcType=VARCHAR},
        #{row.voucherFileId}, #{row.operatorId}, #{row.operatorName,jdbcType=VARCHAR},
        #{row.operatorRole}, #{row.createTime})
      """)
  int insertPayment(@Param("row") WithdrawalWrite.Payment row);

  @Select(
      """
      SELECT amount_cny, actual_amount, actual_currency, paid_at, method, finance_note,
             conversion_note, voucher_file_id, operator_id, operator_name, operator_role
      FROM crm_commission_withdrawal_payment
      WHERE withdrawal_id = #{id}
      """)
  WithdrawalRows.PaymentRow findPayment(@Param("id") long id);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_outbox (
        id, withdrawal_id, event_type, payload, attempts, next_attempt_at, create_time)
      VALUES (
        #{row.id}, #{row.withdrawalId}, #{row.eventType}, CAST(#{row.payload} AS jsonb),
        0, #{row.nextAttemptAt}, #{row.createTime})
      """)
  int insertOutbox(@Param("row") WithdrawalWrite.Outbox row);

  @Select(
      """
      <script>
      INSERT INTO crm_commission_withdrawal_sequence (id, sequence_date, last_value, create_time)
      VALUES (#{id}, #{day}, 1, #{now})
      ON CONFLICT (tenant_id, sequence_date)
      DO UPDATE SET last_value = crm_commission_withdrawal_sequence.last_value + 1
      WHERE crm_commission_withdrawal_sequence.last_value &lt; 999999
      RETURNING last_value
      </script>
      """)
  Integer nextSequence(
      @Param("id") long id, @Param("day") LocalDate day, @Param("now") Instant now);

  /** In-flight withdrawals referencing a payout account (disable checkpoint, task 63). */
  @Select(
      """
      SELECT count(*) FROM crm_commission_withdrawal
      WHERE account_id = #{accountId} AND status IN ('PENDING_REVIEW', 'PENDING_PAYMENT')
      """)
  Long countActiveByAccount(@Param("accountId") long accountId);

  /** Unified finance task list (13.03): the withdrawal branch (contract §6.2). */
  @Select(
      """
      <script>
      SELECT id, kol_id, application_no AS task_no, 'COMMISSION' AS task_type,
             status, amount_cny, create_time AS created_at
      FROM crm_commission_withdrawal
      <where>
        <if test="q.applicationNo != null">AND application_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.withdrawalScope != null">
          AND kol_id IN
          <foreach collection="q.withdrawalScope" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      ORDER BY create_time DESC, id DESC
      LIMIT #{q.limit} OFFSET 0
      </script>
      """)
  List<FinanceTaskRow> pageWithdrawalTasks(@Param("q") FinanceTaskQuery query);

  @Select(
      """
      <script>
      SELECT count(*) FROM crm_commission_withdrawal
      <where>
        <if test="q.applicationNo != null">AND application_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.withdrawalScope != null">
          AND kol_id IN
          <foreach collection="q.withdrawalScope" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      </script>
      """)
  Long countWithdrawalTasks(@Param("q") FinanceTaskQuery query);

  /** Unified finance task list (13.03): the prepay branch (contract §6.2). */
  @Select(
      """
      <script>
      SELECT id, kol_id, prepay_no AS task_no, 'PREPAY' AS task_type, status, amount_cny,
             create_time AS created_at
      FROM crm_prepay
      <where>
        <if test="q.applicationNo != null">AND prepay_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.prepayScope != null">
          AND kol_id IN
          <foreach collection="q.prepayScope" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      ORDER BY create_time DESC, id DESC
      LIMIT #{q.limit} OFFSET 0
      </script>
      """)
  List<FinanceTaskRow> pagePrepayTasks(@Param("q") FinanceTaskQuery query);

  @Select(
      """
      <script>
      SELECT count(*) FROM crm_prepay
      <where>
        <if test="q.applicationNo != null">AND prepay_no = #{q.applicationNo}</if>
        <if test="q.kolId != null">AND kol_id = #{q.kolId}</if>
        <if test="q.status != null">AND status = #{q.status}</if>
        <if test="q.prepayScope != null">
          AND kol_id IN
          <foreach collection="q.prepayScope" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      </where>
      </script>
      """)
  Long countPrepayTasks(@Param("q") FinanceTaskQuery query);

  /** Paid withdrawals of one creator for the fund records page (11.02, task 63). */
  @Select(
      """
      SELECT w.id AS withdrawal_id, w.application_no, w.amount_cny, w.account_masked,
             w.create_time AS submitted_at, p.paid_at
      FROM crm_commission_withdrawal w
      JOIN crm_commission_withdrawal_payment p ON p.withdrawal_id = w.id
      WHERE w.kol_id = #{kolId} AND w.status = 'PAID'
      ORDER BY w.create_time DESC, w.id DESC
      """)
  List<KolPaidWithdrawalRow> listKolPaidWithdrawals(@Param("kolId") long kolId);

  /** One paid withdrawal row of the creator's fund records. */
  record KolPaidWithdrawalRow(
      long withdrawalId,
      String applicationNo,
      BigDecimal amountCny,
      String accountMasked,
      java.time.Instant submittedAt,
      java.time.Instant paidAt) {}

  /** Funding occupation of one creator: pending + paid applications hold this money. */
  @Select(
      """
      SELECT COALESCE(SUM(amount_cny), 0)
      FROM crm_commission_withdrawal
      WHERE kol_id = #{kolId} AND status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID')
      """)
  BigDecimal sumFundingOccupied(@Param("kolId") long kolId);

  /** All funding-status occupations of one creator (order-chain states, task 64 workbench). */
  @Select(
      """
      SELECT a.order_item_id AS order_item_id, w.id AS withdrawal_id,
             w.application_no AS application_no, w.status AS status,
             a.occupied_cny AS occupied_cny, a.source_released_cny AS source_released_cny,
             a.remaining_snapshot_cny AS remaining_snapshot_cny,
             w.create_time AS submitted_at, p.paid_at AS paid_at
      FROM crm_commission_withdrawal_allocation a
      JOIN crm_commission_withdrawal w ON w.id = a.withdrawal_id
      LEFT JOIN crm_commission_withdrawal_payment p ON p.withdrawal_id = w.id
      WHERE w.kol_id = #{kolId}
        AND w.status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID')
      """)
  List<OccupationRow> listOccupationsByKol(@Param("kolId") long kolId);

  /** One persisted order-level snapshot row (13.04: 每单一行). */
  record OrderSnapshotRow(
      long id,
      long orderId,
      String orderNo,
      BigDecimal occupiedCny,
      BigDecimal remainingSnapshotCny) {}

  /** One order-item occupation of one withdrawal, as shown by the order chain. */
  record OccupationRow(
      long orderItemId,
      long withdrawalId,
      String applicationNo,
      String status,
      BigDecimal occupiedCny,
      BigDecimal sourceReleasedCny,
      BigDecimal remainingSnapshotCny,
      Instant submittedAt,
      Instant paidAt) {}

  /** Funding already taken per order item. */
  record ItemOccupation(long orderItemId, BigDecimal occupiedCny) {}

  /** In-flight withdrawal occupation of one KOL. */
  record KolAmountRow(Long kolId, BigDecimal amountCny) {}

  /** Paid commission of one KOL inside a payment window, with the latest payment time. */
  record PaidWindowRow(Long kolId, BigDecimal amountCny, Instant lastTime) {}

  /** One paid withdrawal payment joined to its application. */
  record PaidPaymentRow(
      Long paymentId,
      Long withdrawalId,
      String applicationNo,
      Long kolId,
      BigDecimal amountCny,
      BigDecimal actualAmount,
      String actualCurrency,
      Instant paidAt,
      Long voucherFileId) {}
}
