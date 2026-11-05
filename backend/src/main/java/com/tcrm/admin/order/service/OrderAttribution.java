package com.tcrm.admin.order.service;

import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.product.api.OrderMappingApi;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Order-wide attribution and payment-time BD snapshot; see design §4.6 and task 13 §4.4. */
@Service
public class OrderAttribution {
  private static final Logger LOG = LoggerFactory.getLogger(OrderAttribution.class);
  private final OrderMappingApi mappings;
  private final KolOwnershipApi ownership;

  public OrderAttribution(OrderMappingApi mappings, KolOwnershipApi ownership) {
    this.mappings = mappings;
    this.ownership = ownership;
  }

  public void apply(OrderDO order, List<OrderItemDO> items, boolean force) {
    if (force) {
      items.forEach(
          i -> {
            i.setKolId(null);
            i.setProductId(null);
            i.setKolItemId(null);
            i.setAttributionType(0);
            i.setAttributionTime(null);
            i.setManualReason(null);
          });
    }
    if (order.getPayTime() == null) {
      order.setAttributionStatus("PENDING");
      order.setKolId(null);
      order.setBdUserId(null);
      return;
    }
    for (var item : items) {
      if (item.getAttributionType() != 0) {
        continue;
      }
      var candidates =
          mappings.findAt(order.getShopId(), item.getWeidianItemId(), order.getPayTime());
      if (candidates.size() > 1) {
        LOG.warn(
            "Overlapping order mapping: order={} item={}",
            order.getWeidianOrderId(),
            item.getWeidianItemId());
      }
      if (candidates.size() == 1) {
        var mapping = candidates.getFirst();
        item.setKolId(mapping.kolId());
        item.setProductId(mapping.productId());
        item.setKolItemId(mapping.kolItemId());
        item.setAttributionType(1);
        item.setAttributionTime(Instant.now());
      }
    }
    var ids =
        items.stream().map(OrderItemDO::getKolId).filter(Objects::nonNull).distinct().toList();
    order.setKolId(ids.size() == 1 ? ids.getFirst() : null);
    if (ids.size() > 1) {
      order.setAttributionStatus("CONFLICT");
      items.stream().filter(i -> i.getKolId() != null).forEach(i -> i.setAttributionType(4));
    } else {
      if (!items.isEmpty() && items.stream().allMatch(i -> i.getAttributionType() == 3)) {
        order.setAttributionStatus("NON_KOL");
      } else {
        if (ids.size() == 1 && items.stream().allMatch(i -> i.getKolId() != null)) {
          order.setAttributionStatus("ATTRIBUTED");
        } else {
          order.setAttributionStatus("UNMATCHED");
        }
      }
    }
    order.setBdUserId(
        order.getKolId() == null ? null : ownership.bdAt(order.getKolId(), order.getPayTime()));
  }

  public void manual(OrderDO order, List<OrderItemDO> items, Long kolId, String reason) {
    for (var item : items) {
      item.setKolItemId(manualKolItemId(order, item, kolId));
      item.setKolId(kolId);
      item.setAttributionType(kolId == null ? 3 : 2);
      item.setManualReason(reason);
      item.setAttributionTime(Instant.now());
    }
    order.setKolId(kolId);
    order.setAttributionStatus(kolId == null ? "NON_KOL" : "ATTRIBUTED");
    order.setBdUserId(
        kolId == null || order.getPayTime() == null
            ? null
            : ownership.bdAt(kolId, order.getPayTime()));
  }

  /**
   * Creator item of a manually attributed line (task 112), so the order still counts toward that
   * item's 商品销售额. Looked up by shop + Weidian item id + pay time with {@link
   * OrderMappingApi#findAt}, the same lookup automatic attribution uses, then narrowed to the
   * chosen creator. Written only when exactly one of that creator's items was valid at pay time;
   * none, several, a non-KOL decision or an unpaid order leave it null. {@code findAt} returns at
   * most two rows, so if more than two mappings overlap the chosen creator's item may be cut off
   * and the line stays null — the safe side. Only {@code kol_item_id} is written: {@code
   * product_id} feeds the commission snapshot and stays as it was.
   */
  private Long manualKolItemId(OrderDO order, OrderItemDO item, Long kolId) {
    if (kolId == null
        || order.getPayTime() == null
        || item.getWeidianItemId() == null
        || item.getWeidianItemId().isBlank()) {
      return null;
    }
    var own =
        mappings.findAt(order.getShopId(), item.getWeidianItemId(), order.getPayTime()).stream()
            .filter(mapping -> kolId.equals(mapping.kolId()))
            .toList();
    return own.size() == 1 ? own.getFirst().kolItemId() : null;
  }
}
