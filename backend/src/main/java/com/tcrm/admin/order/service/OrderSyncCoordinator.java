package com.tcrm.admin.order.service;

import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Acquires the same non-waiting per-shop watchdog lock for scheduled and manual work. */
@Service
public class OrderSyncCoordinator {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSyncCoordinator.class);
  private final OrderSync sync;
  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final ShopAccessApi shops;
  private final WeidianAuthorizationApi authorization;
  private final CurrentUserContext current;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean scheduled = new AtomicBoolean(true);

  public OrderSyncCoordinator(
      OrderSync sync,
      DistributedLocks locks,
      EnabledTenantApi tenants,
      ShopAccessApi shops,
      WeidianAuthorizationApi authorization,
      CurrentUserContext current) {
    this.sync = sync;
    this.locks = locks;
    this.tenants = tenants;
    this.shops = shops;
    this.authorization = authorization;
    this.current = current;
  }

  public void trigger(Long shopId) {
    trigger(shopId, null);
  }

  public void trigger(Long shopId, String type) {
    var shop = shops.findShop(shopId);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    if (!Integer.valueOf(1).equals(shop.status())) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
    if (!authorization.authorizedShopIds().contains(shopId)) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    Long tenantId = current.user().tenantId();
    if (tenantId == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var acquired = new CompletableFuture<Boolean>();
    workers.execute(
        () -> {
          try {
            boolean ran =
                locks.run(
                    "order:sync:" + shopId,
                    0,
                    -1,
                    () -> {
                      acquired.complete(true);
                      TenantScope.runCompany(tenantId, () -> sync.synchronize(shopId, type));
                    });
            if (!ran) {
              acquired.complete(false);
            }
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            acquired.complete(false);
          } catch (RuntimeException error) {
            acquired.complete(false);
            LOG.error("Manual order synchronization failed for shop {}", shopId);
          }
        });
    if (!acquired.join()) {
      throw new BusinessException(ErrorCode.ORDER_SYNC_RUNNING);
    }
  }

  public void setScheduled(boolean enabled) {
    scheduled.set(enabled);
  }

  @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
  public void scheduled() {
    if (!scheduled.get()) {
      return;
    }
    TenantScope.runSystem(
        "enumerate enabled companies for order sync",
        () -> {
          for (Long tenantId : tenants.enabledTenantIds()) {
            try {
              TenantScope.runCompany(tenantId, this::company);
            } catch (RuntimeException error) {
              LOG.error("Order synchronization failed for company {}", tenantId);
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
        locks.run("order:sync:" + shop.id(), 0, -1, () -> sync.synchronize(shop.id()));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException error) {
        LOG.error("Order synchronization failed for shop {}", shop.id());
      }
    }
  }

  @PreDestroy
  public void shutdown() {
    workers.shutdownNow();
  }
}
