package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.order.service.SettlementPullPlanner.Action;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.api.EnabledTenantApi;
import com.tcrm.admin.system.model.SystemConfigDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fixed-cadence settlement flow pull (task 76 §3.4). Every five minutes each enabled company's
 * authorized shops are checked against the platform system configuration: the incremental interval
 * ({@code weidian.settlement.sync-interval-minutes}) and the daily Beijing backfill hour ({@code
 * weidian.settlement.backfill-hour}) are read each round, so configuration changes apply without a
 * restart. Per-shop non-waiting watchdog locks prevent double execution; pull failures only log and
 * retry on the next round without touching the order sync.
 */
@Service
public class OrderSettlementSyncCoordinator {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSettlementSyncCoordinator.class);

  private final OrderSettlementFlowService flows;
  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final ShopAccessApi shops;
  private final WeidianAuthorizationApi authorization;
  private final OrderSyncStateMapper states;
  private final SystemConfigApi config;
  private final Clock clock;

  @Autowired
  public OrderSettlementSyncCoordinator(
      OrderSettlementFlowService flows,
      DistributedLocks locks,
      EnabledTenantApi tenants,
      ShopAccessApi shops,
      WeidianAuthorizationApi authorization,
      OrderSyncStateMapper states,
      SystemConfigApi config) {
    this(flows, locks, tenants, shops, authorization, states, config, Clock.systemUTC());
  }

  OrderSettlementSyncCoordinator(
      OrderSettlementFlowService flows,
      DistributedLocks locks,
      EnabledTenantApi tenants,
      ShopAccessApi shops,
      WeidianAuthorizationApi authorization,
      OrderSyncStateMapper states,
      SystemConfigApi config,
      Clock clock) {
    this.flows = flows;
    this.locks = locks;
    this.tenants = tenants;
    this.shops = shops;
    this.authorization = authorization;
    this.states = states;
    this.config = config;
    this.clock = clock;
  }

  @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
  public void scheduled() {
    TenantScope.runSystem(
        "enumerate enabled companies for settlement flow pull",
        () -> {
          for (Long tenantId : tenants.enabledTenantIds()) {
            try {
              TenantScope.runCompany(tenantId, this::company);
            } catch (RuntimeException error) {
              LOG.error("Settlement flow pull failed for company {}", tenantId, error);
            }
          }
        });
  }

  private void company() {
    var authorized = authorization.authorizedShopIds();
    for (var shop : shops.listShops()) {
      if (!Integer.valueOf(1).equals(shop.status()) || !authorized.contains(shop.id())) {
        continue;
      }
      try {
        locks.run("weidian:settlement:" + shop.id(), 0, -1, () -> pullIfDue(shop.id()));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException error) {
        LOG.error("Settlement flow pull failed for shop {}", shop.id(), error);
      }
    }
  }

  /** Pulls whichever action the shop is due for and advances the persisted checkpoints. */
  void pullIfDue(long shopId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    OrderSyncStateDO state = findState(shopId);
    Instant lastPull = state == null ? null : state.getSettlementPullAt();
    Instant lastBackfill = state == null ? null : state.getSettlementBackfillAt();
    Action action =
        SettlementPullPlanner.decide(
            now,
            config.getInt(SystemConfigDefinition.WEIDIAN_SETTLEMENT_SYNC_INTERVAL_MINUTES.key()),
            config.getInt(SystemConfigDefinition.WEIDIAN_SETTLEMENT_BACKFILL_HOUR.key()),
            lastPull,
            lastBackfill);
    if (action == Action.NONE) {
      return;
    }
    if (action == Action.BACKFILL) {
      // The backfill covers the incremental window, so it also counts as this round's pull.
      flows.pull(shopId, now.minus(SettlementPullPlanner.BACKFILL_LOOKBACK), now);
      stamp(shopId, now, now);
    } else {
      flows.pull(shopId, now.minus(SettlementPullPlanner.INCREMENTAL_LOOKBACK), now);
      stamp(shopId, null, now);
    }
  }

  private OrderSyncStateDO findState(long shopId) {
    return states.selectOne(
        new LambdaQueryWrapper<OrderSyncStateDO>().eq(OrderSyncStateDO::getShopId, shopId));
  }

  /** Persists the checkpoints of a successful pull; a failed pull keeps them for the retry. */
  private void stamp(long shopId, Instant backfillAt, Instant pullAt) {
    OrderSyncStateDO state = findState(shopId);
    boolean create = state == null;
    if (create) {
      state = new OrderSyncStateDO();
      state.setShopId(shopId);
    }
    if (backfillAt != null) {
      state.setSettlementBackfillAt(backfillAt);
    }
    if (pullAt != null) {
      state.setSettlementPullAt(pullAt);
    }
    if (create) {
      states.insert(state);
    } else {
      states.updateById(state);
    }
  }
}
