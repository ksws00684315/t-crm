package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickLogDO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemRefDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClickConsumerTest {
  private static final String HASH = "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
  private final ClickStream stream = mock(ClickStream.class);
  private final ClickLogMapper mapper = mock(ClickLogMapper.class);
  private final KolItemApi items = mock(KolItemApi.class);
  private final ClickConsumer consumer =
      new ClickConsumer(stream, mapper, items, Clock.fixed(NOW, ZoneOffset.UTC));

  private static ClickMessage message(String id, long kolItemId, long ts, String ua) {
    Map<String, String> f = new HashMap<>();
    f.put("kolItemId", Long.toString(kolItemId));
    f.put("ts", Long.toString(ts));
    f.put("ipHash", HASH);
    f.put("ua", ua);
    f.put("ref", "");
    f.put("country", "US");
    return new ClickMessage(id, f);
  }

  @SuppressWarnings("unchecked")
  private List<ClickLogDO> inserted() {
    ArgumentCaptor<List<ClickLogDO>> rows = ArgumentCaptor.forClass(List.class);
    verify(mapper).insertIgnore(rows.capture());
    return rows.getValue();
  }

  @Test
  void shouldStoreRowsAndAck_inStreamOrderWithDupAndBotFlags() {
    when(items.listRefsByIds(Set.of(5L, 6L)))
        .thenReturn(List.of(new KolItemRefDTO(5L, 10L, 20L), new KolItemRefDTO(6L, 11L, 21L)));
    String key5 = "click:last:5:" + HASH;
    when(stream.lastClickTimes(List.of(key5, "click:last:6:" + HASH)))
        .thenReturn(Map.of(key5, 990_000L));
    var batch =
        List.of(
            message("1000000-0", 5, 1_000_000, "Mozilla/5.0"), // 10 s after stored: not dup
            message("1000000-1", 5, 1_009_999, "Mozilla/5.0"), // 9,999 ms after previous: dup
            message("1000000-2", 6, 1_000_000, "Googlebot/2.1"), // other link: bot, not dup
            message("1000000-3", 5, 1_019_999, "")); // 10,000 ms after previous: not dup, bot

    assertThat(consumer.process(batch)).isEqualTo(4);

    var rows = inserted();
    assertThat(rows)
        .extracting(ClickLogDO::getId)
        .containsExactly(
            1_000_000_000_000L, 1_000_000_000_001L, 1_000_000_000_002L, 1_000_000_000_003L);
    assertThat(rows).extracting(ClickLogDO::getDup).containsExactly(false, true, false, false);
    assertThat(rows).extracting(ClickLogDO::getBot).containsExactly(false, false, true, true);
    var first = rows.get(0);
    assertThat(first.getClickTime()).isEqualTo(Instant.ofEpochMilli(1_000_000));
    assertThat(first.getKolId()).isEqualTo(10L);
    assertThat(first.getProductId()).isEqualTo(20L);
    assertThat(first.getKolItemId()).isEqualTo(5L);
    assertThat(first.getIpHash()).isEqualTo(HASH);
    assertThat(first.getCountry()).isEqualTo("US");
    assertThat(first.getReferer()).isEmpty();
    assertThat(first.getCreateTime()).isEqualTo(NOW);
    assertThat(rows.get(2).getKolId()).isEqualTo(11L);
    verify(stream).saveLastClickTimes(Map.of(key5, 1_019_999L, "click:last:6:" + HASH, 1_000_000L));
    var order = inOrder(mapper, stream);
    order.verify(mapper).insertIgnore(anyList());
    order.verify(stream).saveLastClickTimes(anyMap());
    order.verify(stream).ack(List.of("1000000-0", "1000000-1", "1000000-2", "1000000-3"));
  }

  @Test
  void shouldKeepLatestTime_whenEventsArriveOutOfOrder() {
    when(items.listRefsByIds(Set.of(5L))).thenReturn(List.of(new KolItemRefDTO(5L, 10L, 20L)));
    String key = "click:last:5:" + HASH;
    when(stream.lastClickTimes(List.of(key))).thenReturn(Map.of(key, 2_000_000L));

    consumer.process(List.of(message("1-0", 5, 1_995_000, "Mozilla/5.0")));

    assertThat(inserted().get(0).getDup()).isTrue();
    verify(stream).saveLastClickTimes(Map.of(key, 2_000_000L));
  }

  @Test
  void shouldAckWithoutStoring_whenMalformedOrUnknownMapping() {
    when(items.listRefsByIds(Set.of(9L))).thenReturn(List.of());
    var malformed = new ClickMessage("2-0", Map.of("kolItemId", "x"));
    var unknown = message("2-1", 9, 1_000, "Mozilla/5.0");

    assertThat(consumer.process(List.of(malformed, unknown))).isZero();

    verify(mapper, never()).insertIgnore(anyList());
    verify(stream).ack(List.of("2-0", "2-1"));
  }

  @Test
  void shouldNotQueryMappings_whenEveryMessageMalformed() {
    consumer.process(List.of(new ClickMessage("3-0", Map.of())));
    verify(items, never()).listRefsByIds(any());
    verify(stream).ack(List.of("3-0"));
  }

  @Test
  void shouldNotAck_whenInsertFails() {
    when(items.listRefsByIds(Set.of(5L))).thenReturn(List.of(new KolItemRefDTO(5L, 10L, 20L)));
    when(stream.lastClickTimes(anyList())).thenReturn(Map.of());
    when(mapper.insertIgnore(anyList())).thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> consumer.process(List.of(message("4-0", 5, 1, "Mozilla/5.0"))))
        .isInstanceOf(IllegalStateException.class);

    verify(stream, never()).saveLastClickTimes(anyMap());
    verify(stream, never()).ack(anyList());
  }

  @Test
  void shouldReadPendingFirst_thenNewEntries() {
    when(items.listRefsByIds(any())).thenReturn(List.of(new KolItemRefDTO(5L, 10L, 20L)));
    when(stream.lastClickTimes(anyList())).thenReturn(Map.of());
    when(stream.readPending(500)).thenReturn(List.of(message("5-0", 5, 1, "Mozilla/5.0")));

    assertThat(consumer.pollOnce()).isEqualTo(1);
    verify(stream, never()).readNew(anyInt(), any());

    when(stream.readPending(500)).thenReturn(List.of());
    when(stream.readNew(500, ClickConsumer.BLOCK)).thenReturn(new ArrayList<>());
    assertThat(consumer.pollOnce()).isZero();
    verify(stream).readNew(500, ClickConsumer.BLOCK);
  }
}
