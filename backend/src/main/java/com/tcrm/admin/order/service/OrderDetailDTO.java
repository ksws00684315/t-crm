package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order detail response (task 40 §3.2, row 09.04). Amounts in yuan; times UTC.
 *
 * @param merchantNo the shop's Weidian shop id shown to the operator
 * @param itemAmount 商品总价: sum of verified historical unit sale prices times quantities; null when
 *     any line price is missing
 * @param payAmount 支付金额: the source order total actually paid (freight included, discounts applied)
 * @param orderAmount 订单金额 = itemAmount + freight (task 112); null when itemAmount is null
 * @param discount 优惠 = orderAmount − payAmount; null when orderAmount or payAmount is null
 * @param refundAmount 退款金额: successful refunds deduplicated by refund id, same algorithm as the
 *     chain {@code refundTotal}; 0.00 without refunds
 * @param netReceived 订单实收 = payAmount − refundAmount; null when payAmount is null
 * @param commission order-level commission block; null without {@code commission:view} or when the
 *     OWN scope does not cover the order (same rule as the chain funds)
 * @param source API or IMPORT (1 / 2 of {@code crm_order.source})
 * @param lastSyncTime last write of this order, sync or import
 */
public record OrderDetailDTO(
    Long id,
    String orderNo,
    String channel,
    Long shopId,
    String shopName,
    String shopCode,
    String merchantNo,
    Long kolId,
    String kolCode,
    String kolName,
    String orderStatus,
    String refundStatus,
    String attributionStatus,
    String source,
    Instant orderTime,
    Instant payTime,
    Instant lastSyncTime,
    Instant createTime,
    BigDecimal itemAmount,
    BigDecimal payAmount,
    BigDecimal freight,
    BigDecimal orderAmount,
    BigDecimal discount,
    BigDecimal refundAmount,
    BigDecimal netReceived,
    CommissionDTO commission,
    List<OrderDetailItemDTO> items) {

  /**
   * Order-level commission of the detail (task 112).
   *
   * @param commissionableAmount 计佣金额: Σ line commissionable amounts; null while any line is
   *     unverified (UI shows 待核实)
   * @param rates distinct pay-time rate snapshots of the lines, in line order; empty when none
   *     accrued
   * @param estimatedCommission 预估佣金: the chain funds {@code kolCommission}; null while any line is
   *     unverified
   */
  record CommissionDTO(
      BigDecimal commissionableAmount, List<BigDecimal> rates, BigDecimal estimatedCommission) {}

  /** One product line of the detail view (row 09.04). */
  record OrderDetailItemDTO(
      Long id,
      String imageUrl,
      String itemName,
      String weidianItemId,
      String skuName,
      Long kolId,
      String kolCode,
      String kolName,
      Integer quantity,
      BigDecimal unitPrice,
      BigDecimal totalAmount,
      BigDecimal payAmount,
      BigDecimal refundAmount,
      String status) {}
}
