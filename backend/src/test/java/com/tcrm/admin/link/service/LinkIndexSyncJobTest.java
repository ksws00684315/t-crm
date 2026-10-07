package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.link.model.LinkIndexScope;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.shared.DistributedLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class LinkIndexSyncJobTest {
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
  private final LinkIndexOutboxMapper outbox = mock(LinkIndexOutboxMapper.class);
  private final LinkIndexService index = mock(LinkIndexService.class);
  private final KolItemApi items = mock(KolItemApi.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final LinkIndexSyncJob job =
      new LinkIndexSyncJob(outbox, index, items, locks, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void locksRunInline() throws InterruptedException {
    when(locks.run(anyString(), anyLong(), anyLong(), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            });
  }

  @Test
  void shouldBackOffAndCollapseWhitespace() {
    assertThat(LinkIndexSyncJob.backoff(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(LinkIndexSyncJob.backoff(2)).isEqualTo(Duration.ofSeconds(30));
    assertThat(LinkIndexSyncJob.backoff(3)).isEqualTo(Duration.ofMinutes(1));
    assertThat(LinkIndexSyncJob.backoff(4)).isEqualTo(Duration.ofMinutes(5));
    assertThat(LinkIndexSyncJob.backoff(5)).isEqualTo(Duration.ofMinutes(10));
    assertThat(LinkIndexSyncJob.backoff(9)).isEqualTo(Duration.ofMinutes(10));
    String text = LinkIndexSyncJob.errorText(new IllegalStateException("redis\ndown  "));
    assertThat(text).isEqualTo("IllegalStateException: redis down");
    assertThat(LinkIndexSyncJob.errorText(new IllegalStateException()))
        .isEqualTo("IllegalStateException:");
    assertThat(LinkIndexSyncJob.errorText(new IllegalStateException("x".repeat(600))).length())
        .isEqualTo(500);
  }

  @Test
  void shouldRefreshMappingUnderKolLock_andDeleteTheRow() throws InterruptedException {
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(1L, LinkIndexScope.MAPPING, 10L, 20L)))
        .thenReturn(List.of());

    job.drain();

    InOrder order = inOrder(locks, index);
    order.verify(locks).run(eq("link:index:kol:10"), eq(120L), eq(-1L), any(Runnable.class));
    order.verify(index).refreshMapping(10L, 20L);
    verify(index, never()).refreshKol(anyLong());
    verify(outbox).deleteIds(Set.of(1L));
    verify(outbox, never()).updateById(any(LinkIndexOutboxDO.class));
  }

  @Test
  void shouldFullRefreshThenMapping_whenBothAreDue() {
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(
            List.of(
                row(1L, LinkIndexScope.KOL, 10L, null), row(2L, LinkIndexScope.MAPPING, 10L, 20L)))
        .thenReturn(List.of());

    job.drain();

    InOrder order = inOrder(index);
    order.verify(index).refreshKol(10L);
    order.verify(index).refreshMapping(10L, 20L);
    verify(outbox).deleteIds(any());
  }

  @Test
  void shouldPageKols_andRefreshEach() {
    List<Long> first = new ArrayList<>();
    for (long id = 1; id <= LinkIndexSyncJob.KOL_PAGE; id++) {
      first.add(id);
    }
    when(items.pageKolIdsWithCurrentProduct(eq(20L), isNull(), eq(LinkIndexSyncJob.KOL_PAGE)))
        .thenReturn(first);
    when(items.pageKolIdsWithCurrentProduct(eq(20L), eq(200L), eq(LinkIndexSyncJob.KOL_PAGE)))
        .thenReturn(List.of(201L));
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(7L, LinkIndexScope.PRODUCT, 20L, null)))
        .thenReturn(List.of());

    job.drain();

    verify(index, times(201)).refreshKol(anyLong());
    verify(index).refreshKol(201L);
    verify(outbox).deleteIds(Set.of(7L));
  }

  @Test
  void shouldDeleteProductRow_whenNoKolMapsIt() {
    when(items.pageKolIdsWithCurrentProduct(eq(20L), isNull(), anyInt())).thenReturn(List.of());
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(7L, LinkIndexScope.PRODUCT, 20L, null)))
        .thenReturn(List.of());

    job.drain();

    verify(index, never()).refreshKol(anyLong());
    verify(outbox).deleteIds(Set.of(7L));
  }

  @Test
  void shouldKeepTheRowAndBackOff_whenRedisWriteFails() {
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(3L, LinkIndexScope.SHOP, 1L, null)))
        .thenReturn(List.of());
    when(items.pageKolIdsWithCurrentShop(eq(1L), isNull(), anyInt())).thenReturn(List.of(10L));
    when(index.refreshKol(10L)).thenThrow(new IllegalStateException("redis down"));

    job.drain();

    ArgumentCaptor<LinkIndexOutboxDO> saved = ArgumentCaptor.forClass(LinkIndexOutboxDO.class);
    verify(outbox).updateById(saved.capture());
    assertThat(saved.getValue().getAttempts()).isEqualTo(1);
    assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(saved.getValue().getLastError()).isEqualTo("IllegalStateException: redis down");
    verify(outbox, never()).deleteIds(any());
  }

  @Test
  void shouldLeaveRowsUntouched_whenInterrupted() throws InterruptedException {
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(4L, LinkIndexScope.KOL, 10L, null)));
    when(locks.run(anyString(), anyLong(), anyLong(), any(Runnable.class)))
        .thenThrow(new InterruptedException("stop"));

    job.drain();

    verify(outbox, never()).deleteIds(any());
    verify(outbox, never()).updateById(any(LinkIndexOutboxDO.class));
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  void shouldSkipScheduledPoll_whenDisabled() throws InterruptedException {
    job.setScheduled(false);
    job.scheduled();
    verify(locks, never()).run(anyString(), anyLong(), anyLong(), any());
  }

  @Test
  void shouldDrainAgain_whileRowsRemainDue() throws InterruptedException {
    when(locks.run(eq(LinkIndexSyncJob.LOCK), eq(0L), eq(-1L), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            });
    when(outbox.listDue(NOW, LinkIndexSyncJob.BATCH))
        .thenReturn(List.of(row(1L, LinkIndexScope.KOL, 10L, null)))
        .thenReturn(List.of(row(2L, LinkIndexScope.KOL, 11L, null)))
        .thenReturn(List.of());

    job.scheduled();

    verify(index).refreshKol(10L);
    verify(index).refreshKol(11L);
    verify(outbox, times(2)).deleteIds(any());
  }

  private static LinkIndexOutboxDO row(
      long id, LinkIndexScope scope, long scopeId, Long productId) {
    LinkIndexOutboxDO row = new LinkIndexOutboxDO();
    row.setId(id);
    row.setScope(scope);
    row.setScopeId(scopeId);
    row.setProductId(productId);
    row.setAttempts(0);
    row.setNextAttemptAt(NOW);
    row.setCreateTime(NOW);
    return row;
  }
}
