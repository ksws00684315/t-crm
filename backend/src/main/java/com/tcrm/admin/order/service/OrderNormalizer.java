package com.tcrm.admin.order.service;

import com.tcrm.admin.integration.api.WeidianBusinessApi.OrderDetailResponse;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Whitelists order fields; never reads names, addresses, phones or stores raw payloads. */
@Component
public class OrderNormalizer {
  private final String salt;

  public OrderNormalizer(@Value("${TCRM_IP_HASH_SALT}") String salt) {
    if (salt.isBlank()) {
      throw new IllegalArgumentException("TCRM_IP_HASH_SALT is required");
    }
    this.salt = salt;
  }

  public OrderPayloadDTO normalize(long shopId, OrderDetailResponse detail, Instant updatedAt) {
    var order = new OrderDO();
    order.setShopId(shopId);
    order.setWeidianOrderId(required(detail.orderId(), 32));
    order.setOrderStatus(OrderStatus.from(detail.status()).name());
    order.setRawStatus(required(detail.status(), 32));
    order.setOrderTime(detail.createdAt());
    order.setPayTime(detail.paidAt());
    order.setTotalAmount(money(detail.total()));
    order.setPayAmount(money(detail.total()));
    order.setFreight(money(decimal(detail.raw(), "express_fee_num")));
    order.setDiscount(BigDecimal.ZERO.setScale(2));
    order.setSource(1);
    order.setSourceUpdateTime(updatedAt);
    order.setLastSyncTime(Instant.now());
    order.setAttributionStatus("PENDING");
    JsonNode buyer = detail.raw() == null ? null : detail.raw().get("buyer_info");
    String buyerId = text(buyer, "buyer_id");
    order.setBuyerHash(buyerId == null || buyerId.isBlank() ? null : hash(salt + buyerId));
    if (detail.createdAt() == null || detail.items() == null || detail.items().isEmpty()) {
      throw invalid();
    }
    var items = new ArrayList<OrderItemDO>();
    int index = 0;
    for (var line : detail.items()) {
      var item = new OrderItemDO();
      String id = first(text(line.raw(), "cur_shop_item_id"), line.itemId());
      item.setWeidianItemId(required(id, 32));
      String sku = first(text(line.raw(), "cur_shop_sku_id"), text(line.raw(), "sku_id"));
      item.setSkuId(sku == null ? "" : required(sku, 32));
      String sub = text(line.raw(), "sub_order_id");
      item.setSubOrderId(
          sub == null || sub.isBlank()
              ? "line-" + hash(id + ":" + item.getSkuId() + ":" + index)
              : required(sub, 128));
      item.setItemName(required(line.itemName(), 255));
      String title = text(line.raw(), "sku_title");
      item.setSkuName(title == null ? "" : required(title, 255));
      if (line.quantity() == null) {
        throw invalid();
      }
      int quantity = line.quantity().intValueExact();
      if (quantity <= 0) {
        throw invalid();
      }
      item.setQuantity(quantity);
      item.setPayAmount(money(line.totalPrice()));
      item.setRefundAmount(BigDecimal.ZERO.setScale(2));
      item.setAttributionType(0);
      items.add(item);
      index++;
    }
    if (items.stream().map(OrderItemDO::getSubOrderId).distinct().count() != items.size()) {
      throw invalid();
    }
    return new OrderPayloadDTO(order, items);
  }

  static String first(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }

  static String text(JsonNode node, String key) {
    JsonNode value = node == null ? null : node.get(key);
    return value == null || value.isNull() ? null : value.asString();
  }

  static BigDecimal decimal(JsonNode node, String key) {
    String v = text(node, key);
    return v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v);
  }

  static BigDecimal money(BigDecimal amount) {
    if (amount == null) {
      throw invalid();
    }
    var result = amount.setScale(2, RoundingMode.HALF_UP);
    if (result.signum() < 0 || result.precision() > 14) {
      throw invalid();
    }
    return result;
  }

  static String required(String value, int max) {
    if (value == null || value.length() > max) {
      throw invalid();
    }
    return value;
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Weidian order data");
  }
}
