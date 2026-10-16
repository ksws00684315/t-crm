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
}
