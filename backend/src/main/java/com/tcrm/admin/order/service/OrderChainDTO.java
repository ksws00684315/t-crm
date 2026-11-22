package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order chain (task 40 §3.4, row 09.04; funds added by task 72, handoff B07–B09): every order line
 * strung together with the copy job, the kol item link, the line itself, its refunds, and the
 * commission side. {@code funds}, the line {@code commission} blocks and {@code financeFlows} are
 * financial data: they come back only for callers holding {@code commission:view} whose OWN scope
 * covers every attributed line of the order (mixed orders hide the amounts, same basis as the
 * settlement details); otherwise all three are null and the UI explains why.
 */
public record OrderChainDTO(
    Long orderId,
    String orderNo,
    FundsDTO funds,
    List<ChainLineDTO> lines,
    List<FlowDTO> financeFlows,
    WeidianReconciliationDTO weidianReconciliation) {

  /** Platform evidence, gated by the same permissions as the order's other financial data. */
  record WeidianReconciliationDTO(
      BigDecimal settlementAmount, Instant settlementAt, List<WeidianFlowDTO> flows) {}

  /** A single posted Weidian row. Settlement metadata is populated only from platform evidence. */
  record WeidianFlowDTO(
      Long id,
      String type,
      BigDecimal amount,
      Instant tradeTime,
      Instant settlementAt,
      String settlementNo) {}

  /**
   * Order-level funds (B07). The shop-profit field is gone (task 81, L-024): it is not displayed.
   *
   * @param refundTotal successful refunds deduplicated by refund id, so one refund spanning several
   *     lines never counts twice
   * @param kolCommission Σ accumulated net commission of the order's live snapshots; null while any
   *     line is unverified (unknown, never a fake zero — same rule as the line blocks)
   * @param accountStatus POSTED when every snapshot row is verified, UNVERIFIED when any row is not
   */
  record FundsDTO(
      BigDecimal payAmount,
      BigDecimal refundTotal,
      BigDecimal kolCommission,
      String accountStatus,
      String financeStatus,
      BigDecimal financeAmount,
      String orderSettlementStatus,
      String commissionStatus) {}

  /** One order line with its chain links. */
  record ChainLineDTO(
      Long orderItemId,
      String subOrderId,
      ChainCopyJobDTO copyJob,
      ChainLinkDTO link,
      Integer quantity,
      BigDecimal payAmount,
      List<ChainRefundDTO> refunds,
      LineCommissionDTO commission) {}

  /**
   * One line's commission block (B07/B09); null without the finance permission or on hidden mixed
   * orders.
   *
   * @param rateSnapshot pay-time rate snapshotted in the latest commission revision; null when
   *     never accrued — never recomputed from the creator's current rate
   * @param rateSupplemented true when the rate came from a super-admin supplement, not from the
   *     promotion config effective at pay time
   * @param netCommission accumulated net commission; null while the line is unverified or has no
   *     snapshot (unknown, distinct from a real zero)
   * @param releaseBasis CONFIRMED / FALLBACK (freeze days prefer confirmation) / UNSETTLED /
   *     AFTER_SALES / UNVERIFIED; null when the line has no snapshot
   * @param occupiedTotal occupation total of PENDING_REVIEW / PENDING_PAYMENT / PAID applications;
   *     rejected and withdrawn allocations are released and never count
   * @param withdrawals every allocation of the line, whatever the application state, so paid,
   *     in-flight and released occupations can be told apart by status
   */
  record LineCommissionDTO(
      BigDecimal rateSnapshot,
      boolean rateSupplemented,
      BigDecimal netCommission,
      String releaseBasis,
      BigDecimal occupiedTotal,
      List<LineWithdrawalDTO> withdrawals) {}

  /** One allocation of the line (B09); {@code paidAt} is null until payment is registered. */
  record LineWithdrawalDTO(
      Long withdrawalId,
      String applicationNo,
      BigDecimal occupiedCny,
      String status,
      Instant paidAt) {}

  /**
   * One finance flow of the order (B08); null without the finance permission or on hidden mixed
   * orders. Since task 159 the rows are the subject postings of {@code crm_finance_flow} (财务流水 科目化,
   * task 158): income subjects at order level and commission subjects at line level.
   *
   * @param flowNo stable reference: {@code FF} + the finance flow id
   * @param subject finance subject code (task 158 §1: PRE_INCOME / FROZEN_INCOME / OUTFLOW /
   *     WITHDRAWABLE_INCOME / COMMISSION_FROZEN / COMMISSION_CLAWBACK / COMMISSION_SETTLEABLE /
   *     ORDER_WITHDRAWN / COMMISSION_WITHDRAWN)
   * @param direction INCREASE / DECREASE of the signed posting
   * @param businessAt business fact time of the flow (payment, refund, settlement, revision,
   *     release or payment-registration time)
   * @param transferredAt money movement time: the registered withdrawal payment time, null before
   *     payment
   * @param withdrawalNo the linked withdrawal application number; null on non-withdrawal flows (UI
   *     shows "-")
   * @param orderStatus deprecated chain status event label, null on subject rows
   * @param commissionStatus deprecated chain status event label, null on subject rows
   * @param settlementAt deprecated settlement event time, null on subject rows
   * @param settlementNo deprecated settlement identifier, null on subject rows
   */
  record FlowDTO(
      String flowNo,
      Long orderItemId,
      String shopName,
      String type,
      String direction,
      BigDecimal amount,
      Instant businessAt,
      Instant transferredAt,
      String withdrawalNo,
      String orderStatus,
      String commissionStatus,
      Instant settlementAt,
      String settlementNo,
      String subject) {}

  /** The copy job that produced the exclusive item; null when no job item wrote the target id. */
  record ChainCopyJobDTO(String jobCode, Instant createTime, String status) {}

  /**
   * The kol item link of the line; null when the line is unattributed.
   *
   * @param kolItemId {@code crm_kol_item.id}
   * @param linkCode link code from {@code crm_kol_item.link_code}
   * @param sourceProductId internal original product identity
   * @param sourceItemId original Weidian item ID, when the source product is known
   * @param targetItemId exclusive (copied) Weidian item id
   */
  record ChainLinkDTO(
      Long kolItemId,
      String linkCode,
      Long sourceProductId,
      String sourceItemId,
      String targetItemId,
      Long kolId,
      String kolCode,
      String kolName) {}

  /** One refund on this line. */
  record ChainRefundDTO(
      Long id,
      String refundNo,
      String refundType,
      String refundStatus,
      BigDecimal amount,
      Instant finishTime) {}
}
