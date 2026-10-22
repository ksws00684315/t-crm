package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-shop incremental ingestion; no transaction spans HTTP calls. Caller holds order:sync lock.
 */
@Service
public class OrderSync {
  private static final Logger LOG = LoggerFactory.getLogger(OrderSync.class);
  private static final Duration OVERLAP = Duration.ofMinutes(10);
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

  @Autowired
  public OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      ShopAccessApi shops,
      ApplicationEventPublisher events,
      TransactionTemplate tx) {
    this(api, states, writer, normalizer, Clock.systemUTC(), shops, events, tx);
  }

  public OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer) {
    this(api, states, writer, normalizer, Clock.systemUTC(), null, null, null);
  }

  OrderSync(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      OrderWriter writer,
      OrderNormalizer normalizer,
      Clock clock) {
    this(api, states, writer, normalizer, clock, null, null, null);
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
    this.api = api;
    this.states = states;
    this.writer = writer;
    this.normalizer = normalizer;
    this.clock = clock;
    this.shops = shops;
    this.events = events;
    this.tx = tx;
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
    try {
      if (syncOrders) {
        orders(shopId, start(state.getOrderCursor(), end), end);
      }
      if (syncRefunds) {
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
      String category =
          cause instanceof BusinessException business
              ? business.getErrorCode().name()
              : "inspect API status logs";
      state.setLastError("Order synchronization failed" + context + ": " + category);
      saveFailure(state, notify, shopId);
      LOG.warn("Order synchronization failed for shop {}", shopId);
      return false;
    }
  }

  /** Persists the failure. A success-to-failure transition notifies super admins once. */
  private void saveFailure(OrderSyncStateDO state, boolean notify, long shopId) {
    String shopName = notify ? shopName(shopId) : "";
    if (tx == null) {
      states.updateById(state);
      return;
    }
    tx.executeWithoutResult(
        status -> {
          states.updateById(state);
          if (notify) {
            publishFailure(shopId, shopName);
          }
        });
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
            saveDetail(shopId, summary.orderId(), summary.updatedAt());
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
                    50,
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
        if (page.totalNum() == null) {
          throw new IllegalStateException("Refund count missing");
        }
        if ((number + 1L) * 50 >= page.totalNum()) {
          break;
        }
        if (page.list().isEmpty()) {
          throw new IllegalStateException("Refund page missing");
        }
        number++;
      }
      from = to;
    }
  }
}
