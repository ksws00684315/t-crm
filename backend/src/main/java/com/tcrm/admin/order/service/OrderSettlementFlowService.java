package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailItem;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailResponse;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import com.tcrm.admin.order.service.SettlementPullPlanner.Window;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingests Weidian settlement detail flows ({@code shop.order.trade.detail}) into {@code
 * crm_order_settlement_flow} and recomputes the commission facts of affected orders (task 76 §3.2 /
 * §3.3). No transaction spans HTTP calls: each page is stored in its own short transaction, and
 * each affected order is re-synced in its own transaction so the commission snapshot and wallet
 * postings commit atomically with the order update.
 *
 * <p>Review rework (task 76): the recompute set is recovered from the persisted flows ({@code
 * first_pulled_at} later than the caller's last successful checkpoint), never from this round's
 * in-memory state — so a pull that dies mid-way, a page-cap abort or a failed recompute cannot
 * strand an order. The caller must advance its checkpoints only when {@link #pull} returns true.
 */
@Service
public class OrderSettlementFlowService {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSettlementFlowService.class);
  private static final int PAGE_SIZE = 100;

  /** Safety valve against a misbehaving pagination; ~20k flows per window and query type. */
  static final int MAX_PAGES_PER_WINDOW = 200;

  private static final int FINGERPRINT_CHUNK = 500;
  private static final List<String> QUERY_TYPES =
      List.of(
          SettlementBasisCalculator.QUERY_INCOME_DETAIL,
          SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND);

  private final WeidianBusinessApi api;
  private final OrderSettlementFlowMapper flows;
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final OrderCommissionSync commissionSync;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public OrderSettlementFlowService(
      WeidianBusinessApi api,
      OrderSettlementFlowMapper flows,
      OrderMapper orders,
      OrderItemMapper items,
      OrderCommissionSync commissionSync,
      TransactionTemplate tx) {
    this(api, flows, orders, items, commissionSync, tx, Clock.systemUTC());
  }

  OrderSettlementFlowService(
      WeidianBusinessApi api,
      OrderSettlementFlowMapper flows,
      OrderMapper orders,
      OrderItemMapper items,
      OrderCommissionSync commissionSync,
      TransactionTemplate tx,
      Clock clock) {
    this.api = api;
    this.flows = flows;
    this.orders = orders;
    this.items = items;
    this.commissionSync = commissionSync;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Pulls both query types for the time range and recomputes the commission facts of every order
   * whose flows were first pulled after {@code sinceCheckpoint} (null = everything). The caller
   * holds the per-shop settlement lock and the company tenant scope.
   *
   * @return true only when the pull and every recompute succeeded; a false return (or a thrown
   *     error) tells the caller to leave the checkpoints in place so the next round retries both
   *     the pull and the recompute — the recompute set recovers from the persisted flows, not from
   *     this round's in-memory state (review rework, task 76)
   */
  public boolean pull(long shopId, Instant start, Instant end, Instant sinceCheckpoint) {
    for (String queryType : QUERY_TYPES) {
      for (Window window : SettlementPullPlanner.windows(start, end)) {
        pullWindow(shopId, window, queryType);
      }
    }
    return recomputeSince(shopId, sinceCheckpoint);
  }

  /**
   * Pages through one window, storing each page in its own transaction. A truncated window (page
   * cap hit) aborts the whole pull: its stored rows are kept, but a basis computed from incomplete
   * flows could be wrong, so nothing is recomputed and the checkpoints stay for the next round.
   */
  private void pullWindow(long shopId, Window window, String queryType) {
    int collected = 0;
    for (int page = 1; page <= MAX_PAGES_PER_WINDOW; page++) {
      TradeDetailResponse response =
          api.tradeDetail(
              shopId,
              new TradeDetailRequest(
                  page,
                  PAGE_SIZE,
                  window.start().toEpochMilli(),
                  window.end().toEpochMilli(),
                  queryType));
      List<TradeDetailItem> rows = rowsOf(response);
      if (rows.isEmpty()) {
        return;
      }
      store(shopId, queryType, rows);
      collected += rows.size();
      Integer total = response.total();
      if (total != null ? collected >= total : rows.size() < PAGE_SIZE) {
        return;
      }
    }
    // The rows already stored stay; aborting keeps the checkpoints and retries next round.
    throw new IllegalStateException(
        "settlement flow page cap "
            + MAX_PAGES_PER_WINDOW
            + " reached for shop "
            + shopId
            + " window "
            + window.start()
            + ".."
            + window.end()
            + " queryType "
            + queryType
            + ": flows may be incomplete, pull aborted without recomputing");
  }

  private static List<TradeDetailItem> rowsOf(TradeDetailResponse response) {
    return response == null || response.list() == null ? List.of() : response.list();
  }

  /**
   * One short transaction per page: inserts flows with new fingerprints and refreshes {@code
   * last_pulled_at} of known ones. Which orders to recompute is decided afterwards from the
   * persisted flows, not from this page.
   */
  private void store(long shopId, String queryType, List<TradeDetailItem> rows) {
    Set<String> remoteIds = new LinkedHashSet<>();
    for (TradeDetailItem row : rows) {
      if (row.orderId() != null && !row.orderId().isBlank()) {
        remoteIds.add(row.orderId());
      }
    }
    Map<String, Long> matched = matchOrders(shopId, remoteIds);
    tx.executeWithoutResult(
        status -> {
          Instant now = clock.instant();
          Set<String> knownFingerprints = new LinkedHashSet<>();
          for (TradeDetailItem row : rows) {
            String fingerprint =
                SettlementFlowFingerprints.of(
                    shopId,
                    queryType,
                    row.orderId(),
                    row.refundId(),
                    row.tradeType(),
                    row.tradeSubType(),
                    row.tradeTime(),
                    row.amount(),
                    row.remark());
            if (findByFingerprint(fingerprint) != null) {
              knownFingerprints.add(fingerprint);
              continue;
            }
            OrderSettlementFlowDO flow = new OrderSettlementFlowDO();
            flow.setId(IdWorker.getId());
            flow.setShopId(shopId);
            flow.setWeidianOrderId(row.orderId());
            flow.setOrderId(matched.get(row.orderId()));
            flow.setRefundId(row.refundId());
            flow.setTradeType(row.tradeType());
            flow.setTradeSubType(row.tradeSubType());
            flow.setTradeTime(row.tradeTime());
            flow.setRemark(row.remark());
            flow.setAmountRaw(row.amount());
            flow.setAmountCny(SettlementBasisCalculator.toCny(row.amount()));
            flow.setQueryType(queryType);
            flow.setFingerprint(fingerprint);
            flow.setFirstPulledAt(now);
            flow.setLastPulledAt(now);
            flows.insert(flow);
          }
          touchKnown(knownFingerprints, now);
        });
  }

  private OrderSettlementFlowDO findByFingerprint(String fingerprint) {
    return flows.selectOne(
        new LambdaQueryWrapper<OrderSettlementFlowDO>()
            .eq(OrderSettlementFlowDO::getFingerprint, fingerprint));
  }

  private void touchKnown(Set<String> fingerprints, Instant now) {
    List<String> chunk = new ArrayList<>();
    for (String fingerprint : fingerprints) {
      chunk.add(fingerprint);
      if (chunk.size() >= FINGERPRINT_CHUNK) {
        flows.touchByFingerprints(chunk, now);
        chunk.clear();
      }
    }
    if (!chunk.isEmpty()) {
      flows.touchByFingerprints(chunk, now);
    }
  }

  /** Exact full-string match only; unmatched flows are stored without an order id. */
  private Map<String, Long> matchOrders(long shopId, Set<String> remoteIds) {
    if (remoteIds.isEmpty()) {
      return Map.of();
    }
    List<OrderDO> found =
        orders.selectList(
            new LambdaQueryWrapper<OrderDO>()
                .select(OrderDO::getId, OrderDO::getWeidianOrderId)
                .eq(OrderDO::getShopId, shopId)
                .in(OrderDO::getWeidianOrderId, remoteIds));
    Map<String, Long> matched = new HashMap<>();
    for (OrderDO order : found) {
      matched.put(order.getWeidianOrderId(), order.getId());
    }
    return matched;
  }

  /**
   * Re-publishes the commission facts of every order whose matched flows were first pulled after
   * {@code sinceCheckpoint}, recovering the set from the persisted flows (review rework, task 76):
   * a pull that dies mid-way, a page-cap abort or a failed recompute all leave the checkpoint in
   * place, so the next round finds these orders here even though their fingerprints are no longer
   * new. Each order commits (or fails) independently; any failure makes the whole pull unsuccessful
   * so the checkpoints stay.
   */
  private boolean recomputeSince(long shopId, Instant sinceCheckpoint) {
    Instant since = sinceCheckpoint == null ? Instant.EPOCH : sinceCheckpoint;
    boolean allOk = true;
    for (Long orderId : flows.listMatchedOrderIdsSince(shopId, since)) {
      try {
        recomputeOne(orderId);
      } catch (RuntimeException error) {
        allOk = false;
        LOG.error(
            "settlement basis recompute failed for shop {} order {}: the pull checkpoint stays,"
                + " it retries next round",
            shopId,
            orderId,
            error);
      }
    }
    return allOk;
  }

  private void recomputeOne(long orderId) {
    tx.executeWithoutResult(
        status -> {
          OrderDO order =
              orders.selectOne(
                  new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getId, orderId).last("FOR UPDATE"));
          if (order == null) {
            return;
          }
          flows.backfillOrderId(
              order.getShopId(), order.getWeidianOrderId(), order.getId(), clock.instant());
          List<OrderItemDO> lines = listItems(order.getId());
          commissionSync.sync(order, lines);
        });
  }

  private List<OrderItemDO> listItems(long orderId) {
    return items.selectList(
        new LambdaQueryWrapper<OrderItemDO>()
            .eq(OrderItemDO::getOrderId, orderId)
            .orderByAsc(OrderItemDO::getId));
  }
}
