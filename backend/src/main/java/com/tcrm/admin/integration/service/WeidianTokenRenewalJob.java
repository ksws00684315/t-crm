package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Renews expiring Weidian access tokens independently for every enabled company and shop. */
@Component
public class WeidianTokenRenewalJob {
  private static final Logger LOG = LoggerFactory.getLogger(WeidianTokenRenewalJob.class);
  static final String LOCK = "weidian:token-renewal";

  private final EnabledTenantApi tenants;
  private final ShopAccessApi shops;
  private final WeidianShopAuthMapper auths;
  private final WeidianTokenCenter tokens;
  private final DistributedLocks locks;
  private final AtomicBoolean scheduled = new AtomicBoolean(true);

  public WeidianTokenRenewalJob(
      EnabledTenantApi tenants,
      ShopAccessApi shops,
      WeidianShopAuthMapper auths,
      WeidianTokenCenter tokens,
      DistributedLocks locks) {
    this.tenants = tenants;
    this.shops = shops;
    this.auths = auths;
    this.tokens = tokens;
    this.locks = locks;
  }

  public void setScheduled(boolean enabled) {
    scheduled.set(enabled);
  }

  @Scheduled(cron = "0 0 * * * *", zone = "UTC")
  public void scheduled() throws InterruptedException {
    if (scheduled.get()) {
      locks.run(LOCK, 0, -1, this::renewEnabledCompanies);
    }
  }

  void renewEnabledCompanies() {
    TenantScope.runSystem(
        "renew Weidian tokens for enabled companies",
        () -> {
          for (Long tenantId : tenants.enabledTenantIds()) {
            try {
              TenantScope.runCompany(tenantId, this::renewCompany);
            } catch (RuntimeException e) {
              LOG.error("Weidian token renewal failed for tenant {}", tenantId);
            }
          }
        });
  }

  private void renewCompany() {
    var enabledShopIds =
        shops.listShops().stream()
            .filter(shop -> Integer.valueOf(1).equals(shop.status()))
            .map(shop -> shop.id())
            .collect(java.util.stream.Collectors.toSet());
    var rows =
        auths.selectList(
            new LambdaQueryWrapper<WeidianShopAuthDO>()
                .eq(WeidianShopAuthDO::getStatus, WeidianAuthStatus.AUTHORIZED.name()));
    for (WeidianShopAuthDO row : rows) {
      if (!enabledShopIds.contains(row.getShopId())) {
        continue;
      }
      try {
        tokens.renewIfDue(row.getShopId());
      } catch (RuntimeException e) {
        LOG.error("Weidian token renewal failed for shop {}", row.getShopId());
      }
    }
  }
}
