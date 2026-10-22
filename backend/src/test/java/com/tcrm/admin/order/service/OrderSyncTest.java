package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderSyncTest {
  @Test
  void shouldSyncOnlyOrders_whenOrderTypeIsSelected() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    var now = Instant.parse("2026-10-16T00:00:00Z");

    assertThat(
            new OrderSync(
                    api,
                    states,
                    mock(OrderWriter.class),
                    new OrderNormalizer("test"),
                    Clock.fixed(now, ZoneOffset.UTC))
                .synchronize(1, "ORDER"))
        .isTrue();

    verify(api).orderList(org.mockito.ArgumentMatchers.eq(1L), any());
    verify(api, org.mockito.Mockito.never())
        .refundList(org.mockito.ArgumentMatchers.anyLong(), any());
    assertThat(state.getOrderCursor()).isEqualTo(now);
    assertThat(state.getRefundCursor()).isNull();
  }

  @Test
  void shouldSyncOnlyRefunds_whenRefundTypeIsSelected() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    var now = Instant.parse("2026-10-16T00:00:00Z");

    assertThat(
            new OrderSync(
                    api,
                    states,
                    mock(OrderWriter.class),
                    new OrderNormalizer("test"),
                    Clock.fixed(now, ZoneOffset.UTC))
                .synchronize(1, "REFUND"))
        .isTrue();

    verify(api, org.mockito.Mockito.never())
        .orderList(org.mockito.ArgumentMatchers.anyLong(), any());
    verify(api).refundList(org.mockito.ArgumentMatchers.eq(1L), any());
    assertThat(state.getOrderCursor()).isNull();
    assertThat(state.getRefundCursor()).isEqualTo(now);
  }

  @Test
  void shouldKeepCursors_whenExternalCallFails() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    Instant before = Instant.parse("2026-10-15T00:00:00Z");
    state.setOrderCursor(before);
    state.setRefundCursor(before);
    when(states.selectOne(any())).thenReturn(state);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenThrow(new IllegalStateException("must not be logged"));
    var sync =
        new OrderSync(
            api,
            states,
            mock(OrderWriter.class),
            new OrderNormalizer("test-only-salt"),
            Clock.fixed(before.plusSeconds(3600), ZoneOffset.UTC));
    assertThat(sync.synchronize(1L)).isFalse();
    assertThat(state.getOrderCursor()).isEqualTo(before);
    assertThat(state.getRefundCursor()).isEqualTo(before);
    assertThat(state.getLastError()).doesNotContain("must not be logged");
  }

  @Test
  void shouldTraverseOfficialCursorsAndAdvanceTogether_whenBatchSucceeds() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var writer = mock(OrderWriter.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    var time = Instant.parse("2026-10-16T00:00:00Z");
    var summary =
        new WeidianBusinessApi.OrderListItem(
            "100", java.math.BigDecimal.TEN, "20", time, time, null);
    var node =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree("{\"next_after_key\":\"official-cursor\"}");
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.OrderListResponse(1, 2, List.of(summary), true, null, node),
            new WeidianBusinessApi.OrderListResponse(1, 2, List.of(summary), false, null, null));
    var detail =
        OrderNormalizerTest.detail(
            "100", "pay", time, List.of(OrderNormalizerTest.line("90", null)));
    when(api.orderDetail(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(detail);
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    var sync =
        new OrderSync(
            api, states, writer, new OrderNormalizer("test"), Clock.fixed(time, ZoneOffset.UTC));
    assertThat(sync.synchronize(1)).isTrue();
    var requests = ArgumentCaptor.forClass(WeidianBusinessApi.OrderListRequest.class);
    verify(api, org.mockito.Mockito.times(2))
        .orderList(org.mockito.ArgumentMatchers.eq(1L), requests.capture());
    assertThat(requests.getAllValues().getFirst().updateStart()).isEqualTo("2026-10-15 08:00:00");
    assertThat(requests.getAllValues().get(1).afterKey()).isEqualTo("official-cursor");
    assertThat(state.getOrderCursor()).isEqualTo(time);
    assertThat(state.getRefundCursor()).isEqualTo(time);
    assertThat(state.getLastError()).isNull();
    assertThat(OrderSync.start(time, time.plusSeconds(1000))).isEqualTo(time.minusSeconds(600));
  }

  @Test
  void shouldSkipUnchangedOrders_whenSummaryWasAlreadyProcessed() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var writer = mock(OrderWriter.class);
    Instant now = Instant.parse("2026-10-16T00:00:00Z");
    var existing = new com.tcrm.admin.order.model.OrderDO();
    existing.setSourceUpdateTime(now);
    when(writer.find(1, "100")).thenReturn(existing);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.OrderListResponse(
                1,
                1,
                List.of(new WeidianBusinessApi.OrderListItem("100", null, null, null, now, null)),
                false,
                null,
                null));
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    assertThat(
            new OrderSync(
                    api,
                    states,
                    writer,
                    new OrderNormalizer("test"),
                    Clock.fixed(now, ZoneOffset.UTC))
                .synchronize(1))
        .isTrue();
    org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
        .orderDetail(org.mockito.ArgumentMatchers.anyLong(), any());
    verify(states).insert(any(OrderSyncStateDO.class));
  }

  @Test
  void shouldRetainCursorAndSafeError_whenPaginationOrIdentityIsInvalid() {
    for (String cursor : new String[] {null, "same"}) {
      var api = mock(WeidianBusinessApi.class);
      var states = mock(OrderSyncStateMapper.class);
      var state = new OrderSyncStateDO();
      when(states.selectOne(any())).thenReturn(state);
      when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
          .thenReturn(
              new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), true, cursor, null));
      assertThat(
              new OrderSync(api, states, mock(OrderWriter.class), new OrderNormalizer("test"))
                  .synchronize(1))
          .isFalse();
      assertThat(state.getOrderCursor()).isNull();
    }
  }

  @Test
  void shouldRetainBothCursors_whenRefundBatchFailsAfterOrdersSucceed() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenThrow(
            new com.tcrm.admin.shared.BusinessException(
                com.tcrm.admin.shared.ErrorCode.WEIDIAN_RATE_LIMITED));
    assertThat(
            new OrderSync(api, states, mock(OrderWriter.class), new OrderNormalizer("test"))
                .synchronize(1))
        .isFalse();
    assertThat(state.getOrderCursor()).isNull();
    assertThat(state.getRefundCursor()).isNull();
    assertThat(state.getLastError()).contains("WEIDIAN_RATE_LIMITED");
  }

  @Test
  void shouldPageRefundsFromZero_whenMoreThanFiftyExist() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var writer = mock(OrderWriter.class);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    var rows =
        java.util.stream.IntStream.range(1, 52)
            .mapToObj(
                n ->
                    new WeidianBusinessApi.RefundListItem(
                        String.valueOf(n), "100", null, null, null))
            .toList();
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.RefundListResponse(rows.subList(0, 50), 51, null),
            new WeidianBusinessApi.RefundListResponse(rows.subList(50, 51), 51, null));
    when(api.refundDetail(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(
            call -> {
              WeidianBusinessApi.RefundDetailRequest req = call.getArgument(1);
              return new WeidianBusinessApi.RefundDetailResponse(
                  null, null, req.refundNo().toString(), "100", java.math.BigDecimal.TEN, null);
            });
    when(writer.find(1, "100")).thenReturn(new com.tcrm.admin.order.model.OrderDO());
    assertThat(new OrderSync(api, states, writer, new OrderNormalizer("test")).synchronize(1))
        .isTrue();
    var pages = ArgumentCaptor.forClass(WeidianBusinessApi.RefundListRequest.class);
    verify(api, org.mockito.Mockito.times(2))
        .refundList(org.mockito.ArgumentMatchers.eq(1L), pages.capture());
    assertThat(
            pages.getAllValues().stream()
                .map(WeidianBusinessApi.RefundListRequest::pageNum)
                .toList())
        .containsExactly(0, 1);
    verify(writer, org.mockito.Mockito.times(51))
        .saveRefund(org.mockito.ArgumentMatchers.eq(1L), any());
  }

  @Test
  void shouldRestoreCursors_whenSuccessfulBatchCheckpointWriteFails() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    Instant old = Instant.parse("2026-10-15T00:00:00Z");
    state.setOrderCursor(old);
    state.setRefundCursor(old);
    state.setLastSuccessAt(old);
    when(states.selectOne(any())).thenReturn(state);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    org.mockito.Mockito.doThrow(new IllegalStateException("checkpoint write failed"))
        .doReturn(1)
        .when(states)
        .updateById(any(OrderSyncStateDO.class));
    var sync =
        new OrderSync(
            api,
            states,
            mock(OrderWriter.class),
            new OrderNormalizer("test"),
            Clock.fixed(old.plusSeconds(3600), ZoneOffset.UTC));
    assertThat(sync.synchronize(1)).isFalse();
    assertThat(state.getOrderCursor()).isEqualTo(old);
    assertThat(state.getRefundCursor()).isEqualTo(old);
    assertThat(state.getLastSuccessAt()).isEqualTo(old);
  }

  @Test
  void shouldContinueSyncAndLeaveAmbiguousItemUnmatched_whenHistoricalMappingsOverlap() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var writer = mock(OrderWriter.class);
    var rows = mock(com.tcrm.admin.product.mapper.KolItemMapper.class);
    var ownership = mock(com.tcrm.admin.kol.api.KolOwnershipApi.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    var first = new com.tcrm.admin.product.model.KolItemDO();
    first.setId(1L);
    first.setKolId(10L);
    var second = new com.tcrm.admin.product.model.KolItemDO();
    second.setId(2L);
    second.setKolId(20L);
    when(rows.selectList(any())).thenReturn(List.of(first, second), List.of(first));
    var attribution =
        new OrderAttribution(
            new com.tcrm.admin.product.service.OrderMappingLookup(rows), ownership);
    var saved = new java.util.ArrayList<OrderPayloadDTO>();
    org.mockito.Mockito.doAnswer(
            call -> {
              OrderPayloadDTO payload = call.getArgument(0);
              attribution.apply(payload.order(), payload.items(), false);
              saved.add(payload);
              return null;
            })
        .when(writer)
        .save(any());
    Instant now = Instant.parse("2026-10-16T00:00:00Z");
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.OrderListResponse(
                2,
                2,
                List.of(
                    new WeidianBusinessApi.OrderListItem("100", null, null, null, now, null),
                    new WeidianBusinessApi.OrderListItem("101", null, null, null, now, null)),
                false,
                null,
                null));
    when(api.orderDetail(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(
            call -> {
              WeidianBusinessApi.OrderDetailRequest request = call.getArgument(1);
              return OrderNormalizerTest.detail(
                  request.orderId().toString(),
                  "pay",
                  now,
                  List.of(OrderNormalizerTest.line("90", null)));
            });
    when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OrderAttribution.class);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThat(
              new OrderSync(
                      api,
                      states,
                      writer,
                      new OrderNormalizer("test-only-salt"),
                      Clock.fixed(now, ZoneOffset.UTC))
                  .synchronize(1))
          .isTrue();
      assertThat(saved).hasSize(2);
      assertThat(saved.getFirst().order().getAttributionStatus()).isEqualTo("UNMATCHED");
      assertThat(saved.getFirst().items().getFirst().getAttributionType()).isZero();
      assertThat(saved.getFirst().items().getFirst().getKolId()).isNull();
      assertThat(saved.get(1).order().getAttributionStatus()).isEqualTo("ATTRIBUTED");
      assertThat(state.getOrderCursor()).isEqualTo(now);
      assertThat(state.getRefundCursor()).isEqualTo(now);
      assertThat(state.getLastError()).isNull();
      assertThat(logs.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage())
                    .isEqualTo("Overlapping order mapping: order=100 item=90");
              });
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void shouldIdentifyOrderAndRetainCursors_whenIdentityOrNormalizationFails() {
    for (boolean mismatch : List.of(true, false)) {
      var api = mock(WeidianBusinessApi.class);
      var states = mock(OrderSyncStateMapper.class);
      var state = new OrderSyncStateDO();
      Instant now = Instant.parse("2026-10-16T00:00:00Z");
      Instant previous = now.minusSeconds(3600);
      state.setOrderCursor(previous);
      state.setRefundCursor(previous);
      when(states.selectOne(any())).thenReturn(state);
      when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
          .thenReturn(
              new WeidianBusinessApi.OrderListResponse(
                  1,
                  1,
                  List.of(new WeidianBusinessApi.OrderListItem("100", null, null, null, now, null)),
                  false,
                  null,
                  null));
      when(api.orderDetail(org.mockito.ArgumentMatchers.anyLong(), any()))
          .thenReturn(OrderNormalizerTest.detail(mismatch ? "101" : "100", "pay", now, List.of()));
      assertThat(
              new OrderSync(
                      api,
                      states,
                      mock(OrderWriter.class),
                      new OrderNormalizer("test"),
                      Clock.fixed(now, ZoneOffset.UTC))
                  .synchronize(1))
          .isFalse();
      assertThat(state.getLastError()).contains("order 100");
      assertThat(state.getOrderCursor()).isEqualTo(previous);
      assertThat(state.getRefundCursor()).isEqualTo(previous);
    }
  }

  @Test
  void shouldAvoidEchoingUntrustedOrderIdentifiers_whenRequestIdIsInvalid() {
    for (String id : java.util.Arrays.asList(null, "untrusted-data\nnot-an-order")) {
      var api = mock(WeidianBusinessApi.class);
      var states = mock(OrderSyncStateMapper.class);
      var state = new OrderSyncStateDO();
      when(states.selectOne(any())).thenReturn(state);
      when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
          .thenReturn(
              new WeidianBusinessApi.OrderListResponse(
                  1,
                  1,
                  List.of(new WeidianBusinessApi.OrderListItem(id, null, null, null, null, null)),
                  false,
                  null,
                  null));
      assertThat(
              new OrderSync(api, states, mock(OrderWriter.class), new OrderNormalizer("test"))
                  .synchronize(1))
          .isFalse();
      assertThat(state.getLastError())
          .contains("for order unknown")
          .doesNotContain("untrusted-data", "not-an-order")
          .hasSizeLessThanOrEqualTo(500);
    }
  }

  @Test
  void shouldKeepApiErrorClassification_whenOrderDetailFails() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var state = new OrderSyncStateDO();
    when(states.selectOne(any())).thenReturn(state);
    when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.OrderListResponse(
                1,
                1,
                List.of(new WeidianBusinessApi.OrderListItem("100", null, null, null, null, null)),
                false,
                null,
                null));
    when(api.orderDetail(org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenThrow(
            new com.tcrm.admin.shared.BusinessException(
                com.tcrm.admin.shared.ErrorCode.WEIDIAN_RATE_LIMITED));
    assertThat(
            new OrderSync(api, states, mock(OrderWriter.class), new OrderNormalizer("test"))
                .synchronize(1))
        .isFalse();
    assertThat(state.getLastError())
        .isEqualTo("Order synchronization failed for order 100: WEIDIAN_RATE_LIMITED");
  }

  @Test
  void shouldRecordPhaseKind_whenSynchronizationFails() {
    for (var failure :
        List.of(
            new PhaseCase("ORDER_SYNC") {
              @Override
              void stub(WeidianBusinessApi api) {
                when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenThrow(new IllegalStateException("boom"));
              }
            },
            new PhaseCase("REFUND_SYNC") {
              @Override
              void stub(WeidianBusinessApi api) {
                when(api.orderList(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(
                        new WeidianBusinessApi.OrderListResponse(
                            0, 0, List.of(), false, null, null));
                when(api.refundList(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenThrow(new IllegalStateException("boom"));
              }
            })) {
      var api = mock(WeidianBusinessApi.class);
      var states = mock(OrderSyncStateMapper.class);
      var state = new OrderSyncStateDO();
      state.setLastSuccessAt(Instant.parse("2026-10-15T00:00:00Z"));
      state.setLastError(null);
      when(states.selectOne(any())).thenReturn(state);
      failure.stub(api);
      var failuresWriter = mock(OrderSyncFailureWriter.class);
      var tx = mock(org.springframework.transaction.support.TransactionTemplate.class);
      org.mockito.Mockito.doAnswer(
              call -> {
                call.getArgument(0, java.util.function.Consumer.class).accept(null);
                return null;
              })
          .when(tx)
          .executeWithoutResult(any());
      var sync =
          new OrderSync(
              api,
              states,
              mock(OrderWriter.class),
              new OrderNormalizer("test"),
              Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC),
              null,
              null,
              tx,
              failuresWriter);
      assertThat(sync.synchronize(1L)).isFalse();
      verify(failuresWriter)
          .record(
              1L,
              com.tcrm.admin.order.model.OrderSyncFailureKind.valueOf(failure.kind),
              "Order synchronization failed: inspect API status logs",
              null);
    }
  }

  private abstract static class PhaseCase {
    final String kind;

    PhaseCase(String kind) {
      this.kind = kind;
    }

    abstract void stub(WeidianBusinessApi api);
  }
}
