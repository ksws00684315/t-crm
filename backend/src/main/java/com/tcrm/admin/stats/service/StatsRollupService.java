package com.tcrm.admin.stats.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsDateChangedEvent;
import com.tcrm.admin.stats.mapper.StatsRollupMapper;
import com.tcrm.admin.stats.mapper.StatsRollupMapper.FactRow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Materializes additive facts and durably records dates that need to be rebuilt. */
@Service
public class StatsRollupService {
  private static final Duration SCAN_OVERLAP = Duration.ofMinutes(10);
  private static final int INSERT_BATCH_SIZE = 1000;
  private final OrderStatsApi source;
  private final StatsRollupMapper mapper;

  public StatsRollupService(OrderStatsApi source, StatsRollupMapper mapper) {
    this.source = source;
    this.mapper = mapper;
  }

  @Transactional
  public void scan(long tenantId, Instant now) {
    Instant checkpoint = mapper.checkpoint(tenantId);
    if (checkpoint != null && !now.isAfter(checkpoint)) {
      return;
    }
    Instant from = checkpoint == null ? Instant.EPOCH : checkpoint.minus(SCAN_OVERLAP);
    var dirty = new TreeSet<>(source.changedDays(tenantId, from, now));
    dirty.add(now.atZone(StatsPeriod.ZONE).toLocalDate());
    for (var day : dirty) {
      mark(tenantId, day, now);
    }
    mapper.advance(IdWorker.getId(), tenantId, now);
  }

  @Transactional
  public void enqueue(long tenantId, LocalDate from, LocalDate until, Instant now) {
    from.datesUntil(until).forEach(day -> mark(tenantId, day, now));
  }

  /** Marks current and former days in the source transaction, before the change becomes visible. */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void markOrderDays(OrderStatsDateChangedEvent event) {
    var dates = new TreeSet<>(source.orderDays(event.tenantId(), event.orderId()));
    if (event.formerTime() != null) {
      dates.add(event.formerTime().atZone(StatsPeriod.ZONE).toLocalDate());
    }
    var now = Instant.now();
    for (var day : dates) {
      mark(event.tenantId(), day, now);
    }
  }

  @Transactional
  public boolean rebuildNext(long tenantId, Instant now) {
    var day = mapper.lockNext(tenantId);
    if (day == null) {
      return false;
    }
    var period = new StatsPeriod(day, day);
    var facts = source.rollup(tenantId, period.from(), period.until());
    mapper.clear(tenantId, day);
    for (int start = 0; start < facts.size(); start += INSERT_BATCH_SIZE) {
      int end = Math.min(start + INSERT_BATCH_SIZE, facts.size());
      var rows =
          facts.subList(start, end).stream()
              .map(fact -> new FactRow(IdWorker.getId(), fact))
              .toList();
      mapper.write(tenantId, day, now, rows);
    }
    mapper.remove(tenantId, day);
    return true;
  }

  private void mark(long tenantId, LocalDate day, Instant now) {
    mapper.mark(IdWorker.getId(), tenantId, day, now);
  }
}
