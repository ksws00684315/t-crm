package com.tcrm.admin.order.api;

import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only order projections for the commission module (task 62, design §6.2 income and
 * settlement-detail interfaces). The caller runs inside the company tenant scope.
 */
public interface OrderSettlementApi {
  /**
   * Pages order ids for the settlement detail list (13.05): newest paid first, deterministic by id.
   * {@code orderNo} is an exact Weidian order number match; blank means no filter. With {@code
   * ownKolIds} (BD OWN scope) only orders whose attributed lines all belong to those KOLs are
   * returned — mixed-attribution aggregates stay hidden from the BD (design §6.2).
   *
   * @param orderNo exact {@code crm_order.weidian_order_id}; null or blank disables the filter
   * @param shopId optional shop filter
   * @param ownKolIds BD-scope KOL ids; null disables the scope
   */
  PageResult<Long> pageOrderIds(
      String orderNo, Long shopId, Collection<Long> ownKolIds, int page, int size);

  /**
   * Orders among {@code ids}; unknown ids are skipped. An empty collection returns an empty list.
   */
  List<OrderRefDTO> listOrdersByIds(Collection<Long> orderIds);

  /** Non-deleted item lines of the orders among {@code ids}, ordered by order and item id. */
  List<ItemRefDTO> listItemsByOrderIds(Collection<Long> orderIds);

  /**
   * Weidian withdrawable amount per order (task 81, display only): the signed sum of the order's
   * INCOME_DETAIL settlement flows in CNY. Orders without such a flow are absent from the map.
   */
  Map<Long, BigDecimal> weidianWithdrawableByOrderIds(Collection<Long> orderIds);

  /**
   * Pays-time window aggregates per KOL for the income list (11.02): order count (distinct paid
   * orders with at least one line of the KOL) and quantity (line pieces).
   */
  List<KolOrderRowDTO> aggregateKolOrders(Collection<Long> kolIds, Instant start, Instant end);

  /** Order projection for display; {@code payAmount} includes freight. */
  record OrderRefDTO(
      long id,
      String orderNo,
      long shopId,
      Instant payTime,
      String orderStatus,
      BigDecimal payAmount) {}

  /** Item line projection for display. */
  record ItemRefDTO(
      long id,
      long orderId,
      String itemName,
      String skuName,
      int quantity,
      BigDecimal payAmount,
      BigDecimal refundAmount,
      Long kolId,
      Long productId) {}

  /** Order count and pieces of one KOL inside the pay-time window (both bounds optional). */
  record KolOrderRowDTO(long kolId, long orderCount, long quantity) {}
}
