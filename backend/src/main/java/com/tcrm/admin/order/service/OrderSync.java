package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.order.mapper.OrderSyncRetryMapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncFailureKind;
import com.tcrm.admin.order.model.OrderSyncRetryDO;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-shop incremental ingestion; no transaction spans HTTP calls. Caller holds order:sync lock.
 *
 * <p>A failing order detail during the order phase skips that order instead of failing the shop
 * (task 145, audit B-5): the order goes to {@code crm_order_sync_retry} and is retried on every
 * later run. Errors that concern the whole shop (not connected, bad credentials, rate limit), list
 * calls and the refund phase still fail the run and keep both cursors.
 */
@Service
public class OrderSync {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSync.class);
  private static final Duration OVERLAP = Duration.ofMinutes(10);
  private static final int REFUND_PAGE_SIZE = 50;
  private static final int MAX_REFUND_PAGES = 200;
  private static final int RETRY_BATCH = 100;
  private static final int RETRY_NOTIFY_AT = 3;
  private static final Set<ErrorCode> SHOP_WIDE_ERRORS =
      Set.of(
          ErrorCode.WEIDIAN_APP_NOT_CONFIGURED,
          ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED,
          ErrorCode.WEIDIAN_CREDENTIAL_INVALID,
          ErrorCode.WEIDIAN_RATE_LIMITED,
          ErrorCode.WEIDIAN_API_FAILED);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
  private final WeidianBusinessApi api;
  private final OrderSyncStateMapper states;
  private final OrderWriter writer;
  private final OrderNormalizer normalizer;
  private final Clock clock;
  private final ShopAccessApi shops;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final OrderSyncFailureWriter failures;
  private final OrderSyncRetryMapper retries;

  @Autowired
  public OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      ShopAccessApi shops,
      ApplicationEventPublisher events,
      TransactionTemplate tx,
      OrderSyncFailureWriter failures,
      OrderSyncRetryMapper retries) {
    this(api, states, writer, normalizer, Clock.systemUTC(), shops, events, tx, failures, retries);
  }

  public OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer) {
    this(api, states, writer, normalizer, Clock.systemUTC(), null, null, null, null, null);
  }

  OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      Clock clock) {
    this(api, states, writer, normalizer, clock, null, null, null, null, null);
  }

  OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      Clock clock,
      ShopAccessApi shops,
      ApplicationEventPublisher events,
      TransactionTemplate tx) {
    this(api, states, writer, normalizer, clock, shops, events, tx, null, null);
  }

  OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      Clock clock,
      ShopAccessApi shops,
      ApplicationEventPublisher events,
      TransactionTemplate tx,
      OrderSyncFailureWriter failures,
      OrderSyncRetryMapper retries) {
    this.api = api;
    this.states = states;
    this.writer = writer;
    this.normalizer = normalizer;
    this.clock = clock;
    this.shops = shops;
    this.events = events;
    this.tx = tx;
    this.failures = failures;
    this.retries = retries;
  }

  public boolean synchronize(long shopId) {
    return synchronize(shopId, null);
  }

  public boolean synchronize(long shopId, String type) {
    boolean syncOrders = type == null || "ORDER".equals(type);
    boolean syncRefunds = type == null || "REFUND".equals(type);
    var state =
        states.selectOne(
            new LambdaQueryWrapper<OrderSyncStateDO>().eq(OrderSyncStateDO::getShopId, shopId));
    if (state == null) {
      state = new OrderSyncStateDO();
      state.setShopId(shopId);
      states.insert(state);
    }
    Instant end = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Instant previousOrderCursor = state.getOrderCursor();
    Instant previousRefundCursor = state.getRefundCursor();
    Instant previousSuccess = state.getLastSuccessAt();
    boolean notify = state.getLastError() == null && previousSuccess != null;
    String phase = OrderSyncFailureKind.ORDER_SYNC.name();
    try {
      if (syncOrders) {
        orders(shopId, start(state.getOrderCursor(), end), end);
      }
      if (syncRefunds) {
        phase = OrderSyncFailureKind.REFUND_SYNC.name();
        refunds(shopId, start(state.getRefundCursor(), end), end);
      }
      if (syncOrders) {
        state.setOrderCursor(end);
      }
      if (syncRefunds) {
        state.setRefundCursor(end);
      }
      state.setLastSuccessAt(clock.instant());
      state.setLastError(null);
      states.updateById(state);
      return true;
    } catch (RuntimeException error) {
      state.setOrderCursor(previousOrderCursor);
      state.setRefundCursor(previousRefundCursor);
      state.setLastSuccessAt(previousSuccess);
      Throwable cause = error instanceof OrderFailure ? error.getCause() : error;
      String context = error instanceof OrderFailure failed ? " for order " + failed.orderId : "";
      String category = category(cause);
      state.setLastError("Order synchronization failed" + context + ": " + category);
      saveFailure(state, notify, shopId, phase, category);
      LOG.warn("Order synchronization failed for shop {}", shopId);
      return false;
    }
  }

  /** Persists the failure. A success-to-failure transition notifies super admins once. */
  private void saveFailure(
      OrderSyncStateDO state, boolean notify, long shopId, String phase, String category) {
    String shopName = notify ? shopName(shopId) : "";
    if (tx == null) {
      states.updateById(state);
      return;
    }
    tx.executeWithoutResult(
        status -> {
          states.updateById(state);
          if (failures != null) {
            failures.record(
                shopId,
                OrderSyncFailureKind.valueOf(phase),
                "Order synchronization failed: " + category,
                null);
          }
          if (notify) {
            publishFailure(shopId, shopName);
          }
        });
  }

  private static String category(Throwable cause) {
    return cause instanceof BusinessException business
        ? business.getErrorCode().name()
        : "inspect API status logs";
  }

  private void publishFailure(long shopId, String shopName) {
    if (events == null) {
      return;
    }
    Long tenant = TenantScope.companyId();
    if (tenant == null) {
      LOG.warn("Order sync failure for shop {} has no company; notification skipped", shopId);
      return;
    }
    TransactionalEvents.publish(events, new OrderSyncFailedEvent(tenant, shopId, shopName));
  }

  private String shopName(long shopId) {
    if (shops == null) {
      return "";
    }
    try {
      ShopAccessDTO shop = shops.findShop(shopId);
      if (shop == null || shop.name() == null) {
        return "";
      }
      return shop.name();
    } catch (RuntimeException ex) {
      LOG.warn("Shop {} name unavailable ({})", shopId, ex.getClass().getSimpleName());
      return "";
    }
  }

  static Instant start(Instant cursor, Instant end) {
    return cursor == null ? end.minus(Duration.ofHours(24)) : cursor.minus(OVERLAP);
  }

  private void orders(long shopId, Instant from, Instant end) {
    Map<String, OrderSyncRetryDO> pending = pendingRetries(shopId);
    while (from.isBefore(end)) {
      Instant to = from.plus(Duration.ofDays(89));
      if (to.isAfter(end)) {
        to = end;
      }
      String after = null;
      var seen = new HashSet<String>();
      do {
        var page =
            api.orderList(
                shopId,
                new WeidianBusinessApi.OrderListRequest(
                    null,
                    true,
                    after,
                    "all",
                    50,
                    TIME.format(to),
                    null,
                    null,
                    TIME.format(from),
                    "0",
                    null));
        for (var summary : page.orders()) {
          var existing = writer.find(shopId, summary.orderId());
          if (existing == null
              || summary.updatedAt() == null
              || existing.getSourceUpdateTime() == null
              || summary.updatedAt().isAfter(existing.getSourceUpdateTime())) {
            syncOrder(shopId, summary.orderId(), summary.updatedAt(), pending);
          }
        }
        if (!Boolean.TRUE.equals(page.hasMore())) {
          break;
        }
        // 09a preserves the official next_after_key in raw, while its typed accessor reads
        // after_key.
        after =
            OrderNormalizer.first(
                OrderNormalizer.text(page.raw(), "next_after_key"), page.nextAfterKey());
        if (after == null || after.isBlank() || !seen.add(after)) {
          throw new IllegalStateException("Invalid order pagination cursor");
        }
      } while (true);
      from = to;
    }
    // Orders that failed on an earlier run and were not in this run's lists.
    for (var retry : new ArrayList<>(pending.values())) {
      syncOrder(shopId, retry.getOrderId(), null, pending);
    }
  }

  private Map<String, OrderSyncRetryDO> pendingRetries(long shopId) {
    var pending = new HashMap<String, OrderSyncRetryDO>();
    if (retries == null) {
      return pending;
    }
    for (var retry : retries.listPending(shopId, RETRY_BATCH)) {
      pending.put(retry.getOrderId(), retry);
    }
    return pending;
  }

  /**
   * Saves one order; a failure that concerns only this order is recorded for retry and skipped, one
   * that concerns the whole shop fails the run.
   */
  private void syncOrder(
      long shopId, String id, Instant updatedAt, Map<String, OrderSyncRetryDO> pending) {
    try {
      saveDetail(shopId, id, updatedAt);
    } catch (OrderFailure failure) {
      if (failure.getCause() instanceof BusinessException business
          && SHOP_WIDE_ERRORS.contains(business.getErrorCode())) {
        throw failure;
      }
      skip(shopId, failure, pending.remove(id));
      return;
    }
    OrderSyncRetryDO resolved = pending.remove(id);
    if (resolved != null) {
      retries.deleteResolved(resolved.getId());
    }
  }

  private void skip(long shopId, OrderFailure failure, OrderSyncRetryDO known) {
    String error =
        "Order synchronization failed for order "
            + failure.orderId
            + ": "
            + category(failure.getCause());
    LOG.warn("Order {} skipped in synchronization of shop {}", failure.orderId, shopId);
    if (retries == null || "unknown".equals(failure.orderId)) {
      // An identifier that is not a Weidian order number cannot be fetched again.
      recordSkip(shopId, error, false);
      return;
    }
    OrderSyncRetryDO retry =
        known != null
            ? known
            : retries.selectOne(
                new LambdaQueryWrapper<OrderSyncRetryDO>()
                    .eq(OrderSyncRetryDO::getShopId, shopId)
                    .eq(OrderSyncRetryDO::getOrderId, failure.orderId));
    int count;
    if (retry == null) {
      retry = new OrderSyncRetryDO();
      retry.setShopId(shopId);
      retry.setOrderId(failure.orderId);
      retry.setFailCount(1);
      retry.setLastError(error);
      retries.insert(retry);
      count = 1;
    } else {
      count = retry.getFailCount() + 1;
      retry.setFailCount(count);
      retry.setLastError(error);
      retries.updateById(retry);
    }
    // The failure feed gets the first failure and the one that notifies, not every 5-minute retry.
    if (count == 1 || count == RETRY_NOTIFY_AT) {
      recordSkip(shopId, error, count == RETRY_NOTIFY_AT);
    }
  }

  private void recordSkip(long shopId, String error, boolean notify) {
    String shopName = notify ? shopName(shopId) : "";
    if (tx == null) {
      return;
    }
    tx.executeWithoutResult(
        status -> {
          if (failures != null) {
            failures.record(shopId, OrderSyncFailureKind.ORDER_SYNC, error, null);
          }
          if (notify) {
            publishFailure(shopId, shopName);
          }
        });
  }

  private void saveDetail(long shopId, String id, Instant updatedAt) {
    try {
      var detail =
          api.orderDetail(shopId, new WeidianBusinessApi.OrderDetailRequest(Long.valueOf(id)));
      if (!Objects.equals(id, detail.orderId())) {
        throw new IllegalStateException("Order identity mismatch");
      }
      writer.save(normalizer.normalize(shopId, detail, updatedAt));
    } catch (RuntimeException error) {
      throw new OrderFailure(id, error);
    }
  }

  private static final class OrderFailure extends RuntimeException {
    private final String orderId;

    private OrderFailure(String orderId, RuntimeException cause) {
      super("Order synchronization failed", cause);
      this.orderId = orderId != null && orderId.matches("[0-9]{1,32}") ? orderId : "unknown";
    }
  }

  private void refunds(long shopId, Instant from, Instant end) {
    while (from.isBefore(end)) {
      Instant to = from.plus(Duration.ofDays(89));
      if (to.isAfter(end)) {
        to = end;
      }
      int number = 0;
      while (true) {
        var page =
            api.refundList(
                shopId,
                new WeidianBusinessApi.RefundListRequest(
                    null,
                    null,
                    null,
                    TIME.format(from),
                    TIME.format(to),
                    number,
                    REFUND_PAGE_SIZE,
                    null,
                    null,
                    null));
        for (var summary : page.list()) {
          var detail =
              api.refundDetail(
                  shopId,
                  new WeidianBusinessApi.RefundDetailRequest(Long.valueOf(summary.refundNo())));
          if (!Objects.equals(summary.refundNo(), detail.refundNo())
              || !Objects.equals(summary.orderId(), detail.orderId())) {
            throw new IllegalStateException("Refund identity mismatch");
          }
          if (writer.find(shopId, detail.orderId()) == null) {
            saveDetail(shopId, detail.orderId(), null);
          }
          writer.saveRefund(shopId, detail);
        }
        if (page.totalNum() != null) {
          if ((number + 1L) * REFUND_PAGE_SIZE >= page.totalNum()) {
            break;
          }
          if (page.list().isEmpty()) {
            throw new IllegalStateException("Refund page missing");
          }
        } else if (page.list().size() < REFUND_PAGE_SIZE) {
          // Weidian omits totalNum when the window has no refunds (task 55 live test),
          // so a short page is the only end marker.
          break;
        }
        number++;
        if (number >= MAX_REFUND_PAGES) {
          throw new IllegalStateException(
              "Refund pagination exceeded " + MAX_REFUND_PAGES + " pages in one window");
        }
      }
      from = to;
    }
  }
}
