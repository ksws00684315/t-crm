package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.DailyFactDTO;
import com.tcrm.admin.order.api.OrderStatsDateChangedEvent;
import com.tcrm.admin.stats.mapper.StatsRollupMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsRollupServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T17:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
  private static final LocalDate PRIOR = TODAY.minusDays(1);
  private final StatsRollupMapper mapper = mock(StatsRollupMapper.class);
  private final OrderStatsApi source = mock(OrderStatsApi.class);
  private final StatsRollupService service = new StatsRollupService(source, mapper);

  @Test
  void scanPersistsDirtyDatesBeforeWatermarkAndUsesAnOverlap() {
    when(source.changedDays(4, Instant.EPOCH, NOW)).thenReturn(List.of(PRIOR, PRIOR, TODAY));
    service.scan(4, NOW);
    var sequence = inOrder(mapper);
    sequence.verify(mapper).mark(anyLong(), eq(4L), eq(PRIOR), eq(NOW));
    sequence.verify(mapper).mark(anyLong(), eq(4L), eq(TODAY), eq(NOW));
    sequence.verify(mapper).advance(anyLong(), eq(4L), eq(NOW));

    when(mapper.checkpoint(4)).thenReturn(NOW);
    service.scan(4, NOW);
    var later = NOW.plusSeconds(300);
    service.scan(4, later);
    verify(source).changedDays(4, NOW.minusSeconds(600), later);
  }

  @Test
  void rebuildWritesBoundedBatchesThenDequeuesOnlyOnSuccess() {
    when(mapper.lockNext(4)).thenReturn(TODAY).thenReturn(null);
    var zero = BigDecimal.ZERO;
    var fact = new DailyFactDTO("ORDER", 1, 0, 0, "", "", 1, 0, zero, zero, zero);
    when(source.rollup(eq(4L), any(), any())).thenReturn(Collections.nCopies(1001, fact));
    assertThat(service.rebuildNext(4, NOW)).isTrue();
    assertThat(service.rebuildNext(4, NOW)).isFalse();
    var sequence = inOrder(mapper);
    sequence.verify(mapper).clear(4, TODAY);
    sequence
        .verify(mapper, org.mockito.Mockito.times(2))
        .write(eq(4L), eq(TODAY), eq(NOW), anyList());
    sequence.verify(mapper).remove(4, TODAY);
  }

  @Test
  void retainsDirtyDateWhenWritingFails() {
    when(mapper.lockNext(4)).thenReturn(TODAY);
    var zero = BigDecimal.ZERO;
    when(source.rollup(eq(4L), any(), any()))
        .thenReturn(List.of(new DailyFactDTO("ITEM", 1, 2, 3, "A", "B", 0, 1, zero, zero, zero)));
    doThrow(IllegalStateException.class).when(mapper).write(eq(4L), eq(TODAY), eq(NOW), anyList());
    assertThatThrownBy(() -> service.rebuildNext(4, NOW)).isInstanceOf(IllegalStateException.class);
    verify(mapper, never()).remove(4, TODAY);
  }

  @Test
  void enqueuesPeriodicRangeAndFormerBeijingDay() {
    service.enqueue(4, PRIOR, TODAY, NOW);
    when(source.orderDays(4, 8)).thenReturn(List.of(TODAY));
    service.markOrderDays(new OrderStatsDateChangedEvent(4, 8, NOW));
    service.markOrderDays(new OrderStatsDateChangedEvent(4, 9, null));
    verify(mapper).mark(anyLong(), eq(4L), eq(PRIOR), eq(NOW));
    verify(mapper).mark(anyLong(), eq(4L), eq(TODAY), any());
  }
}
