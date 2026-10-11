package com.tcrm.admin.link.service;

import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickLogDO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.shared.TenantScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns {@code stream:click} batches into {@code crm_click_log} rows (task 06 §4.5). Order per
 * batch: insert (ON CONFLICT DO NOTHING) → store the latest click times → XACK. Any failure leaves
 * the batch unacknowledged in the pending list; replaying it is harmless because click ids are
 * derived from the message IDs.
 */
@Service
public class ClickConsumer {
  static final int BATCH_SIZE = 500;
  static final Duration BLOCK = Duration.ofSeconds(2);
  private static final Logger LOG = LoggerFactory.getLogger(ClickConsumer.class);
  private final ClickStream stream;
  private final ClickLogMapper clickLogs;
  private final KolItemApi kolItems;
  private final Clock clock;

  @Autowired
  public ClickConsumer(ClickStream stream, ClickLogMapper clickLogs, KolItemApi kolItems) {
    this(stream, clickLogs, kolItems, Clock.systemUTC());
  }

  ClickConsumer(ClickStream stream, ClickLogMapper clickLogs, KolItemApi kolItems, Clock clock) {
    this.stream = stream;
    this.clickLogs = clickLogs;
    this.kolItems = kolItems;
    this.clock = clock;
  }

  /**
   * Reads and stores one batch: the pending list first (redelivery after a failure or restart),
   * otherwise new entries, blocking up to 2 s. Returns the number of messages handled.
   */
  public int pollOnce() {
    List<ClickMessage> batch = stream.readPending(BATCH_SIZE);
    if (batch.isEmpty()) {
      batch = stream.readNew(BATCH_SIZE, BLOCK);
    }
    if (!batch.isEmpty()) {
      process(batch);
    }
    return batch.size();
  }

  /** Stores a batch and acknowledges every message in it; returns the rows sent to the database. */
  int process(List<ClickMessage> batch) {
    return TenantScope.callSystem("consume clicks across companies", () -> processInScope(batch));
  }

  private int processInScope(List<ClickMessage> batch) {
    long started = System.nanoTime();
    List<ClickEvent> events = new ArrayList<>();
    for (ClickMessage message : batch) {
      ClickEvent.parse(message.id(), message.fields())
          .ifPresentOrElse(
              events::add, () -> LOG.warn("Skipped malformed click event {}", message.id()));
    }
    Map<Long, KolItemRefDTO> refs = refs(events);
    List<ClickEvent> known = new ArrayList<>();
    for (ClickEvent event : events) {
      if (refs.containsKey(event.kolItemId())) {
        known.add(event);
      } else {
        LOG.warn(
            "Skipped click event {}: kol item {} not found", event.messageId(), event.kolItemId());
      }
    }
    Map<String, Long> latest = new LinkedHashMap<>();
    List<ClickLogDO> rows = toRows(known, refs, latest);
    if (!rows.isEmpty()) {
      clickLogs.insertIgnore(rows);
    }
    stream.saveLastClickTimes(latest);
    stream.ack(batch.stream().map(ClickMessage::id).toList());
    LOG.debug(
        "Stored {} of {} click events in {} ms",
        rows.size(),
        batch.size(),
        (System.nanoTime() - started) / 1_000_000);
    return rows.size();
  }

  private Map<Long, KolItemRefDTO> refs(List<ClickEvent> events) {
    var ids = events.stream().map(ClickEvent::kolItemId).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return kolItems.listRefsByIds(ids).stream()
        .collect(Collectors.toMap(KolItemRefDTO::kolItemId, Function.identity(), (a, b) -> a));
  }

  /**
   * Builds rows in stream order. The dup check compares with the previous click of the same link
   * and IP hash: earlier in this batch, else the stored {@code click:last:*} time, else the latest
   * {@code crm_click_log} time in the batch window. {@code latest} receives the newest event time
   * per key to store afterwards.
   */
  private List<ClickLogDO> toRows(
      List<ClickEvent> events, Map<Long, KolItemRefDTO> refs, Map<String, Long> latest) {
    var keys = new LinkedHashSet<String>();
    events.forEach(event -> keys.add(event.lastClickKey()));
    Map<String, Long> previous = new HashMap<>(stream.lastClickTimes(new ArrayList<>(keys)));
    seedFromClickLog(events, refs, previous);
    Instant now = clock.instant();
    List<ClickLogDO> rows = new ArrayList<>();
    for (ClickEvent event : events) {
      String key = event.lastClickKey();
      Long last = previous.get(key);
      boolean dup = ClickRules.isDup(last, event.clickMillis());
      long newest = last == null ? event.clickMillis() : Math.max(last, event.clickMillis());
      previous.put(key, newest);
      latest.put(key, newest);
      rows.add(row(event, refs.get(event.kolItemId()), dup, now));
    }
    return rows;
  }

  /**
   * Redis {@code click:last:*} is only an accelerator. Keys missing from this batch's lookup are
   * filled from one click-log query over {@code [earliest event - 10s, latest event]}.
   */
  private void seedFromClickLog(
      List<ClickEvent> events, Map<Long, KolItemRefDTO> refs, Map<String, Long> previous) {
    Map<String, ClickLogDO> misses = new LinkedHashMap<>();
    long earliest = Long.MAX_VALUE;
    long latest = Long.MIN_VALUE;
    for (ClickEvent event : events) {
      earliest = Math.min(earliest, event.clickMillis());
      latest = Math.max(latest, event.clickMillis());
      String key = event.lastClickKey();
      if (previous.containsKey(key) || misses.containsKey(key)) {
        continue;
      }
      KolItemRefDTO ref = refs.get(event.kolItemId());
      ClickLogDO probe = new ClickLogDO();
      probe.setKolId(ref.kolId());
      probe.setProductId(ref.productId());
      probe.setKolItemId(event.kolItemId());
      probe.setIpHash(event.ipHash());
      misses.put(key, probe);
    }
    if (misses.isEmpty()) {
      return;
    }
    List<ClickLogDO> found =
        clickLogs.latestClickTimes(
            new ArrayList<>(misses.values()),
            Instant.ofEpochMilli(earliest - ClickRules.DUP_WINDOW_MILLIS),
            Instant.ofEpochMilli(latest));
    for (ClickLogDO row : found) {
      previous.putIfAbsent(
          "click:last:" + row.getKolItemId() + ":" + row.getIpHash(),
          row.getClickTime().toEpochMilli());
    }
  }

  private static ClickLogDO row(ClickEvent event, KolItemRefDTO ref, boolean dup, Instant now) {
    var row = new ClickLogDO();
    row.setId(event.id());
    row.setClickTime(Instant.ofEpochMilli(event.clickMillis()));
    row.setKolId(ref.kolId());
    row.setProductId(ref.productId());
    row.setKolItemId(event.kolItemId());
    row.setIpHash(event.ipHash());
    row.setUa(event.ua());
    row.setReferer(event.referer());
    row.setCountry(event.country());
    row.setBot(ClickRules.isBot(event.ua()));
    row.setDup(dup);
    row.setCreateTime(now);
    row.setTenantId(ref.tenantId());
    return row;
  }
}
