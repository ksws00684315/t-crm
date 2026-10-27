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

  /** Flow types of {@link FlowDTO#type()}; shop receivables wait for the M0 net-amount hookup. */
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
   */
  record OrderCommissionChainDTO(
      BigDecimal netCommissionCny,
      boolean allVerified,
      List<LineCommissionDTO> lines,
      List<FlowDTO> flows) {}

  /**
   * One line's commission block (B07/B09).
   *
   * @param orderItemId {@code crm_order_item.id}
   * @param rateSnapshot pay-time rate of the line's latest revision; null when never accrued —
   *     never recomputed from the KOL's current rate
   * @param netCommissionCny accumulated net commission; null while the line is unverified or has no
   *     snapshot (unknown, distinct from a real zero)
   * @param releaseBasis CONFIRMED / FALLBACK / UNSETTLED / AFTER_SALES / UNVERIFIED; null when the
   *     line has no snapshot
   * @param occupiedCny occupation total of {@link #OCCUPYING_STATUSES} applications; rejected and
   *     withdrawn allocations never count
   * @param withdrawals every allocation of the line, whatever the application state, so the UI can
   *     tell paid, in-flight and released occupations apart by status
   */
  record LineCommissionDTO(
      long orderItemId,
      BigDecimal rateSnapshot,
      BigDecimal netCommissionCny,
      String releaseBasis,
      BigDecimal occupiedCny,
      List<WithdrawalRefDTO> withdrawals) {}

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
   * @param type {@link #FLOW_KOL_COMMISSION}; {@link #FLOW_SHOP_RECEIVABLE} stays reserved for M0
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
}
