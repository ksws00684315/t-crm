package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Read-only commission view of one order for the admin chain (task 72, design §6.2, handoff
 * B07–B09). Port owned by the {@code order} module and implemented by {@code commission}, mirroring
 * {@link CommissionOrderStateApi}: the order module never depends on commission classes, so the
 * module graph stays acyclic. The caller runs inside the company tenant scope; permission and
 * OWN-scope gating stay with the caller, so this port returns raw company data.
 */
public interface OrderChainCommissionApi {

  /**
   * Records an authorized missing rate; caller holds the company order lock and validates
   * ownership.
   */
  void supplementRate(
      long tenantId,
      long orderId,
      long itemId,
      long kolId,
      BigDecimal rate,
      String reason,
      long actorId);

  /** Shop receivables are assembled by the order module from payments and refunds. */
  String FLOW_SHOP_RECEIVABLE = "SHOP_RECEIVABLE";

  String FLOW_KOL_COMMISSION = "KOL_COMMISSION";

  /** Flow directions; the flow amount itself is always positive. */
  String DIRECTION_INCREASE = "INCREASE";

  String DIRECTION_DECREASE = "DECREASE";

  /** Withdrawal statuses whose allocations still count as occupied (ADR-032 §6, released = not). */
  List<String> OCCUPYING_STATUSES = List.of("PENDING_REVIEW", "PENDING_PAYMENT", "PAID");

  /**
   * Commission data of one order: the order summary, one line block per requested item id, and the
   * finance flows. Lines without a commission snapshot come back with null rate/commission and an
   * empty occupation.
   *
   * @param orderId {@code crm_order.id}
   * @param orderItemIds the order's item line ids; every id gets a {@link LineCommissionDTO}
   */
  OrderCommissionChainDTO chainOfOrder(long orderId, Collection<Long> orderItemIds);

  /**
   * Order-level commission summary.
   *
   * @param netCommissionCny Σ accumulated net commission of the order's live snapshots (unverified
   *     rows contribute 0; same basis as the settlement details)
   * @param allVerified every live snapshot is verified (true when the order has none)
   * @param commissionableCny Σ line commissionable amounts (task 112, 计佣金额); null unless every
   *     requested line has a verified snapshot
   * @param afterSales any live snapshot of the order is in after-sales (task 113), verified or not
   */
  record OrderCommissionChainDTO(
      BigDecimal netCommissionCny,
      boolean allVerified,
      List<LineCommissionDTO> lines,
      List<FlowDTO> flows,
      BigDecimal commissionableCny,
      boolean afterSales) {}

  /**
   * One line's commission block (B07/B09).
   *
   * @param orderItemId {@code crm_order_item.id}
   * @param rateSnapshot pay-time rate of the line's latest revision; null when never accrued —
   *     never recomputed from the KOL's current rate
   * @param rateSupplemented true when that rate came from a super-admin supplement rather than the
   *     promotion config in force at pay time; the UI marks those lines so reconciliation can tell
   *     the rule apart from an override
   * @param netCommissionCny accumulated net commission; null while the line is unverified or has no
   *     snapshot (unknown, distinct from a real zero)
   * @param releaseBasis CONFIRMED / FALLBACK (freeze days prefer confirmation) / UNSETTLED /
   *     AFTER_SALES / UNVERIFIED; null when the line has no snapshot
   * @param occupiedCny occupation total of {@link #OCCUPYING_STATUSES} applications; rejected and
   *     withdrawn allocations never count
   * @param withdrawals every allocation of the line, whatever the application state, so the UI can
   *     tell paid, in-flight and released occupations apart by status
   * @param commissionableCny commissionable amount of the line (task 112, 计佣金额): the effective
   *     basis of the snapshot, successful refunds already deducted — the amount the net commission
   *     is accrued from; null while the line is unverified or has no snapshot
   */
  record LineCommissionDTO(
      long orderItemId,
      BigDecimal rateSnapshot,
      boolean rateSupplemented,
      BigDecimal netCommissionCny,
      String releaseBasis,
      BigDecimal occupiedCny,
      List<WithdrawalRefDTO> withdrawals,
      BigDecimal commissionableCny) {}

  /** One allocation of the line, with the payment fact when the application is paid (B09). */
  record WithdrawalRefDTO(
      long withdrawalId,
      String applicationNo,
      BigDecimal occupiedCny,
      String status,
      Instant paidAt) {}

  /**
   * One finance flow of the order (B08). Amounts are always positive; the direction carries the
   * sign. Payout flows split per withdrawal allocation, so partial and repeated withdrawals of one
   * line each keep their own application number and payment time.
   *
   * @param orderItemId the line the flow belongs to
   * @param flowNo stable, traceable number: {@code CL{ledgerId}} for wallet entries, {@code
   *     WA{allocationId}} for withdrawal allocations
   * @param type {@link #FLOW_KOL_COMMISSION} for the commission-side wallet flows
   * @param direction {@link #DIRECTION_INCREASE} or {@link #DIRECTION_DECREASE}
   * @param amountCny magnitude of the flow, scale 2
   * @param businessAt the business fact time (ledger {@code occurred_at}; allocation = application
   *     submission)
   * @param transferredAt money movement time: the registered payment time of the withdrawal, null
   *     before payment
   * @param withdrawalNo the linked withdrawal application number; null on non-withdrawal flows
   */
  record FlowDTO(
      long orderItemId,
      String flowNo,
      String type,
      String direction,
      BigDecimal amountCny,
      Instant businessAt,
      Instant transferredAt,
      String withdrawalNo) {}

  /**
   * Subject-labeled finance flows of one order (task 159, 财务流转口径规则 task 158 §5.2): the entries of
   * {@code crm_finance_flow}, income subjects at order level and commission subjects at line level.
   * Withdrawal-paid entries split per allocation. The KOL-level 佣金已提现 subject is not part of one
   * order's listing; its order-level counterpart 订单已提现 is. The amount is signed: positive increases
   * the subject.
   */
  List<FinanceFlowDTO> financeFlows(long orderId);

  /** One finance flow entry of the order. */
  record FinanceFlowDTO(
      String flowNo,
      String subject,
      String type,
      Long orderItemId,
      Long kolId,
      BigDecimal amountCny,
      Instant businessAt,
      Instant transferredAt,
      String withdrawalNo) {}
}
