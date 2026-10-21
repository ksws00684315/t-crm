package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order chain (task 40 §3.4, row 09.04): every order line strung together with the copy job, the
 * kol item link, the line itself, its refunds, and the commission side. {@code ledger} and {@code
 * withdrawals} are always null — the commission module is not built yet and the UI shows 开发中.
 */
public record OrderChainDTO(Long orderId, String orderNo, List<ChainLineDTO> lines) {

  /** One order line with its chain links. */
  record ChainLineDTO(
      Long orderItemId,
      String subOrderId,
      ChainCopyJobDTO copyJob,
      ChainLinkDTO link,
      Integer quantity,
      BigDecimal payAmount,
      List<ChainRefundDTO> refunds,
      Object ledger,
      Object withdrawals) {}

  /** The copy job that produced the exclusive item; null when no job item wrote the target id. */
  record ChainCopyJobDTO(String jobCode, Instant createTime, String status) {}

  /**
   * The kol item link of the line; null when the line is unattributed.
   *
   * @param kolItemId {@code crm_kol_item.id}
   * @param linkCode link code; null until the link module's column lands (task 39 merge)
   * @param sourceProductId original product id
   * @param targetItemId exclusive (copied) Weidian item id
   */
  record ChainLinkDTO(
      Long kolItemId,
      String linkCode,
      Long sourceProductId,
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
