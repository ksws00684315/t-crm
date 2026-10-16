package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reconciles late inserted ownership intervals without changing KOL attribution; design §4.6. */
@Service
public class OrderSnapshotCorrection {
  static final String CHECKPOINT = "order:bd-snapshot:checkpoint";
  private static final Logger LOG = LoggerFactory.getLogger(OrderSnapshotCorrection.class);
  private final KolOwnershipApi history;
  private final OrderMapper orders;
  private final EnabledTenantApi tenants;
  private final DistributedLocks locks;
  private final StringRedisTemplate redis;
  private final AtomicBoolean scheduled = new AtomicBoolean(true);

  public OrderSnapshotCorrection(
      KolOwnershipApi history,
      OrderMapper orders,
      EnabledTenantApi tenants,
      DistributedLocks locks,
      StringRedisTemplate redis) {
    this.history = history;
    this.orders = orders;
    this.tenants = tenants;
    this.locks = locks;
    this.redis = redis;
  }

  public void setScheduled(boolean enabled) {
    scheduled.set(enabled);
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "UTC")
  public void scheduled() throws InterruptedException {
    if (scheduled.get()) {
      locks.run("order:bd-snapshot", 0, -1, this::correct);
    }
  }

  public void correct() {
    Instant end = Instant.now();
    String stored = redis.opsForValue().get(CHECKPOINT);
    Instant from = stored == null ? end.minus(Duration.ofHours(24)) : Instant.parse(stored);
    // Re-read a small overlap for histories committed late; all updates are idempotent.
    Instant start = stored == null ? from : from.minus(Duration.ofMinutes(10));
    try {
      TenantScope.runSystem(
          "enumerate enabled companies for order BD snapshot correction",
          () -> {
            for (Long tenant : tenants.enabledTenantIds()) {
              TenantScope.runCompany(
                  tenant,
                  () -> {
                    for (var interval : history.createdBetween(start, end)) {
                      var update =
                          new LambdaUpdateWrapper<OrderDO>()
                              .eq(OrderDO::getKolId, interval.kolId())
                              .ge(OrderDO::getPayTime, interval.startTime())
                              .lt(
                                  interval.endTime() != null,
                                  OrderDO::getPayTime,
                                  interval.endTime())
                              .set(OrderDO::getBdUserId, interval.bdUserId())
                              .set(OrderDO::getUpdateTime, end);
                      orders.update(null, update);
                    }
                  });
            }
          });
      redis.opsForValue().set(CHECKPOINT, end.toString());
    } catch (RuntimeException error) {
      LOG.error("Order BD snapshot correction failed; checkpoint retained");
      throw error;
    }
  }
}
