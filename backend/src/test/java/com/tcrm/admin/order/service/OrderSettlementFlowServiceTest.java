package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.tcrm.admin.shared.TestTransactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests of the settlement flow pull service (task 76 §3.2, review rework): window splitting,
 * dedup insertion, exact order matching, the page-limit safety valve and the persisted recovery of
 * the recompute set. {@code pull()} always queries both query types, so every stub is keyed by
 * {@code queryType} to isolate the two branches.
 */
class OrderSettlementFlowServiceTest {
  private static final Instant START = Instant.parse("2026-10-27T00:00:00Z");
  private static final Instant END = Instant.parse("2026-10-28T00:00:00Z");

  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class, OrderSettlementFlowDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private final WeidianBusinessApi api = mock(WeidianBusinessApi.class);
  private final OrderSettlementFlowMapper flows = mock(OrderSettlementFlowMapper.class);
  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final OrderCommissionSync commissionSync = mock(OrderCommissionSync.class);
  private final TransactionTemplate tx = TestTransactions.template();

  private OrderSettlementFlowService service(Instant now) {
    return new OrderSettlementFlowService(
        api, flows, orders, items, commissionSync, tx, Clock.fixed(now, java.time.ZoneOffset.UTC));
  }

  /** Stubs the trade detail responses per query type; {@code income} applies to INCOME_DETAIL. */
  private void stubDetail(TradeDetailItem... income) {
    when(api.tradeDetail(
            anyLong(),
            ArgumentMatchers.argThat(
                r -> SettlementBasisCalculator.QUERY_INCOME_DETAIL.equals(queryType(r)))))
        .thenReturn(new TradeDetailResponse(List.of(income), income.length, null));
    when(api.tradeDetail(
            anyLong(),
            ArgumentMatchers.argThat(
                r -> SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND.equals(queryType(r)))))
        .thenReturn(new TradeDetailResponse(List.of(), 0, null));
  }

  private static String queryType(TradeDetailRequest request) {
    return request == null ? null : request.queryType();
  }

  /** Stubs the recompute path: locked order row plus its item lines. */
  private void stubRecompute() {
    when(orders.selectOne(any(LambdaQueryWrapper.class)))
        .thenReturn(orderRow(50L, "t76_wd_order_1"));
    when(items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
  }

  @Test
  void shouldPullBothQueryTypesAndMatchOrdersExactly() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(anyLong(), any())).thenReturn(List.of(50L));
    when(orders.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(orderRow(50L, "t76_wd_order_1")));
    stubRecompute();
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, null);

    assertThat(ok).isTrue();
    // The exact order id matched the local order and got order_id backfilled for the recompute.
    org.mockito.ArgumentCaptor<OrderSettlementFlowDO> captor =
        org.mockito.ArgumentCaptor.forClass(OrderSettlementFlowDO.class);
    verify(flows).insert(captor.capture());
    assertThat(captor.getValue().getOrderId()).isEqualTo(50L);
    assertThat(captor.getValue().getWeidianOrderId()).isEqualTo("t76_wd_order_1");
    verify(commissionSync).sync(any(), any());
    // The recompute set came from the persisted flows since the checkpoint.
    verify(flows).listMatchedOrderIdsSince(30L, Instant.EPOCH);
    // Both query types were asked for.
    verify(api, org.mockito.Mockito.times(2))
        .tradeDetail(anyLong(), ArgumentMatchers.any(TradeDetailRequest.class));
  }

  @Test
  void shouldRecomputePersistedFlowsAfterPreviousFailure() {
    // Review finding 1: fingerprints of the stranded round are no longer new, yet the recompute
    // set recovers from the database (flows stored after the never-advanced checkpoint).
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new OrderSettlementFlowDO());
    when(flows.listMatchedOrderIdsSince(30L, Instant.EPOCH)).thenReturn(List.of(50L));
    stubRecompute();
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, null);

    assertThat(ok).isTrue();
    verify(flows, never()).insert(any(OrderSettlementFlowDO.class));
    verify(commissionSync).sync(any(), any());
  }

  @Test
  void shouldMarkFailedOrderAndLetTheCheckpointAdvance() {
    // Task 91 (L-023): a failing recompute is recorded on the order and no longer holds the
    // shop's checkpoint back (the pull returns true), so the caller advances it.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(30L, Instant.EPOCH)).thenReturn(List.of(50L));
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    stubRecompute();
    when(orders.settlementRecomputeFailures(50L)).thenReturn(1);
    org.mockito.Mockito.doThrow(new RuntimeException("wallet down"))
        .when(commissionSync)
        .sync(any(), any());
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, null);

    assertThat(ok).isTrue();
    verify(commissionSync).sync(any(), any());
    verify(orders).markSettlementRecomputeFailed(50L);
    verify(orders, never()).clearSettlementRecomputeFailed(anyLong());
  }

  @Test
  void shouldRecomputeTheOtherOrdersWhenOneFails() {
    // Order 50 fails, order 51 is unaffected: it recomputes, nothing is marked for it.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(30L, Instant.EPOCH)).thenReturn(List.of(50L, 51L));
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(orders.selectOne(any(LambdaQueryWrapper.class)))
        .thenReturn(orderRow(50L, "t91_wd_order_50"), orderRow(51L, "t91_wd_order_51"));
    when(items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(orders.settlementRecomputeFailures(50L)).thenReturn(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (invocation.<OrderDO>getArgument(0).getId() == 50L) {
                throw new RuntimeException("recompute broke");
              }
              return null;
            })
        .when(commissionSync)
        .sync(any(), any());
    stubDetail(detail("t91_wd_order_50", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, null);

    assertThat(ok).isTrue();
    verify(commissionSync, org.mockito.Mockito.times(2)).sync(any(), any());
    verify(orders).markSettlementRecomputeFailed(50L);
    verify(orders, never()).markSettlementRecomputeFailed(51L);
  }

  @Test
  void shouldRetryOnlyTheMarkedOrderAndClearItsMarkOnSuccess() {
    // Next round: the checkpoint moved past the old flows, nothing new arrived, but order 50
    // carries a retry mark. Only it is recomputed; success clears the mark.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new OrderSettlementFlowDO());
    Instant checkpoint = Instant.parse("2026-10-27T12:00:00Z");
    when(flows.listMatchedOrderIdsSince(30L, checkpoint)).thenReturn(List.of());
    when(orders.listSettlementRecomputePending(30L)).thenReturn(List.of(50L));
    stubRecompute();
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, checkpoint);

    assertThat(ok).isTrue();
    verify(commissionSync).sync(any(), any());
    verify(orders).clearSettlementRecomputeFailed(50L);
    verify(orders, never()).markSettlementRecomputeFailed(anyLong());
  }

  @Test
  void shouldKeepTheMarkAndCountAgainWhenTheRetryFailsAgain() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new OrderSettlementFlowDO());
    Instant checkpoint = Instant.parse("2026-10-27T12:00:00Z");
    when(flows.listMatchedOrderIdsSince(30L, checkpoint)).thenReturn(List.of());
    when(orders.listSettlementRecomputePending(30L)).thenReturn(List.of(50L));
    stubRecompute();
    when(orders.settlementRecomputeFailures(50L)).thenReturn(3);
    org.mockito.Mockito.doThrow(new RuntimeException("still broken"))
        .when(commissionSync)
        .sync(any(), any());
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, checkpoint);

    // One attempt per round, never a loop; the pull still counts as done.
    assertThat(ok).isTrue();
    verify(commissionSync).sync(any(), any());
    verify(orders).markSettlementRecomputeFailed(50L);
    verify(orders, never()).clearSettlementRecomputeFailed(anyLong());
  }

  @Test
  void shouldKeepTheCheckpointWhenTheRetryMarkCannotBeWritten() {
    // Nothing would remember the order once the checkpoint moves on, so the old behavior stays:
    // the pull is reported unsuccessful and the recompute set recovers from the persisted flows.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(30L, Instant.EPOCH)).thenReturn(List.of(50L));
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    stubRecompute();
    org.mockito.Mockito.doThrow(new RuntimeException("wallet down"))
        .when(commissionSync)
        .sync(any(), any());
    when(orders.markSettlementRecomputeFailed(50L)).thenThrow(new RuntimeException("db down"));
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    boolean ok = service(START.plusSeconds(60)).pull(30L, START, END, null);

    assertThat(ok).isFalse();
  }

  @Test
  void shouldNotRecomputeOrMarkAnythingWhenTheWeidianPullFails() {
    // Task 76 review rule kept: a failed pull throws before any recompute, so the caller leaves
    // the checkpoints in place.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(api.tradeDetail(anyLong(), any(TradeDetailRequest.class)))
        .thenThrow(new RuntimeException("weidian down"));

    OrderSettlementFlowService underTest = service(START.plusSeconds(60));

    assertThatThrownBy(() -> underTest.pull(30L, START, END, null))
        .isInstanceOf(RuntimeException.class);
    verify(commissionSync, never()).sync(any(), any());
    verify(orders, never()).markSettlementRecomputeFailed(anyLong());
  }

  @Test
  void shouldUseCheckpointAsRecomputeLowerBound() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new OrderSettlementFlowDO());
    when(flows.listMatchedOrderIdsSince(anyLong(), any())).thenReturn(List.of());
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));
    Instant checkpoint = Instant.parse("2026-10-27T12:00:00Z");

    service(START.plusSeconds(60)).pull(30L, START, END, checkpoint);

    // Flows pulled before the last successful checkpoint are not recomputed again.
    verify(flows).listMatchedOrderIdsSince(30L, checkpoint);
    verify(commissionSync, never()).sync(any(), any());
  }

  @Test
  void shouldNotMatchSuffixOrderIds() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(anyLong(), any())).thenReturn(List.of());
    // The local order exists for the plain id; the pulled row uses the refund-suffixed id, which
    // must stay unmatched (exact match only, no suffix truncation).
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    stubDetail(detail("t76_wd_order_1_refund", "支出", "退款", "-600"));

    service(START.plusSeconds(60)).pull(30L, START, END, null);

    org.mockito.ArgumentCaptor<OrderSettlementFlowDO> captor =
        org.mockito.ArgumentCaptor.forClass(OrderSettlementFlowDO.class);
    verify(flows).insert(captor.capture());
    assertThat(captor.getValue().getOrderId()).isNull();
  }

  @Test
  void shouldDeduplicateKnownFingerprintsInsteadOfInserting() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new OrderSettlementFlowDO());
    when(flows.listMatchedOrderIdsSince(anyLong(), any())).thenReturn(List.of());
    stubDetail(detail("t76_wd_order_1", "收入", "货款收入", "600"));

    service(START.plusSeconds(60)).pull(30L, START, END, null);

    verify(flows, never()).insert(any(OrderSettlementFlowDO.class));
    verify(flows).touchByFingerprints(any(), any());
  }

  @Test
  void shouldStopAtReportedTotalAcrossPages() {
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(flows.listMatchedOrderIdsSince(anyLong(), any())).thenReturn(List.of());
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    stubDetail(
        detail("t76_wd_order_1", "收入", "货款收入", "600"),
        detail("t76_wd_order_1", "支出", "交易手续费", "-4"));

    service(START.plusSeconds(60)).pull(30L, START, END, null);

    verify(flows, org.mockito.Mockito.times(2)).insert(any(OrderSettlementFlowDO.class));
  }

  @Test
  void shouldAbortPullWhenPageCapIsHit() {
    // Review finding 1, page-cap branch: the stored rows are kept, but the pull aborts (no
    // recompute on incomplete data) and the caller must not advance the checkpoints.
    when(flows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    java.util.List<TradeDetailItem> fullPage = new java.util.ArrayList<>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(detail("t76_wd_order_1", "收入", "货款收入", "600"));
    }
    when(api.tradeDetail(
            anyLong(),
            ArgumentMatchers.argThat(
                r -> SettlementBasisCalculator.QUERY_INCOME_DETAIL.equals(queryType(r)))))
        .thenAnswer(
            invocation -> new TradeDetailResponse(new java.util.ArrayList<>(fullPage), null, null));
    when(api.tradeDetail(
            anyLong(),
            ArgumentMatchers.argThat(
                r -> SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND.equals(queryType(r)))))
        .thenReturn(new TradeDetailResponse(List.of(), 0, null));

    OrderSettlementFlowService underTest = service(START.plusSeconds(60));

    assertThatThrownBy(() -> underTest.pull(30L, START, END, null))
        .isInstanceOf(IllegalStateException.class);
    // A missing total never satisfies the stop condition, so the 200-page cap ends the loop;
    // every stored row keeps its insert (200 pages x 100 rows), none triggered a recompute.
    verify(flows, org.mockito.Mockito.times(20_000)).insert(any(OrderSettlementFlowDO.class));
    verify(commissionSync, never()).sync(any(), any());
  }

  private static OrderDO orderRow(long id, String remoteId) {
    OrderDO order = new OrderDO();
    order.setId(id);
    order.setShopId(30L);
    order.setWeidianOrderId(remoteId);
    return order;
  }

  private static TradeDetailItem detail(
      String orderId, String type, String subType, String amount) {
    return new TradeDetailItem(
        orderId, null, type, subType, new BigDecimal(amount), START.plusSeconds(1), "", null);
  }
}
