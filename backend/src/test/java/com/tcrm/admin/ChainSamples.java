package com.tcrm.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds Weidian envelopes from the checked-in open-platform samples, then substitutes this test's
 * ids, amounts and times. The samples keep the field names; the chain tests do not invent a second
 * response shape.
 */
final class ChainSamples {
  static final DateTimeFormatter WEIDIAN_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ChainSamples() {}

  /** Item list sample {@code item-list-10550-v1.1.json}; {@code itemid} and {@code total_num}. */
  static String itemList(String sellerId, String firstItemId, String secondItemId) {
    ObjectNode root = load("/weidian/item-list-10550-v1.1.json");
    ObjectNode result = (ObjectNode) root.get("result");
    result.put("item_num", 2);
    result.put("total_num", 2);
    ArrayNode items = (ArrayNode) result.get("items");
    ObjectNode first = (ObjectNode) items.get(0);
    first.put("itemid", firstItemId);
    first.put("seller_id", sellerId);
    first.put("item_name", "t79-a");
    ObjectNode second = first.deepCopy();
    second.put("itemid", secondItemId);
    second.put("item_name", "t79-b");
    items.add(second);
    return root.toString();
  }

  /** Order list sample {@code order-list-10668-v1.6.json}. The sample omits {@code has_more}. */
  static String orderList(String orderId, Instant when, String total) {
    ObjectNode root = load("/weidian/order-list-10668-v1.6.json");
    ObjectNode result = (ObjectNode) root.get("result");
    result.put("order_num", 1);
    result.put("total_num", 1);
    result.put("has_more", false);
    ObjectNode order = (ObjectNode) result.get("orders").get(0);
    order.put("order_id", orderId);
    order.put("total", total);
    order.put("time", WEIDIAN_TIME.format(when));
    order.put("update_time", WEIDIAN_TIME.format(when));
    return root.toString();
  }

  static String emptyOrderList() {
    ObjectNode root = load("/weidian/order-list-10668-v1.6.json");
    ObjectNode result = (ObjectNode) root.get("result");
    result.put("order_num", 0);
    result.put("total_num", 0);
    result.put("has_more", false);
    result.set("orders", JSON.createArrayNode());
    return root.toString();
  }

  /**
   * Order detail sample {@code order-detail-1064-v2.0.json}. The sample's numeric status {@code 20}
   * is replaced with {@code pay}: {@code OrderStatus} maps the detail string {@code pay} to PAID
   * (design §8.1). Field names and the {@code items} array stay on the sample.
   */
  static String orderDetail(
      String orderId,
      Instant when,
      String itemA,
      String payA,
      String itemB,
      String payB,
      String freight,
      String total) {
    ObjectNode root = load("/weidian/order-detail-1064-v2.0.json");
    ObjectNode result = (ObjectNode) root.get("result");
    String text = WEIDIAN_TIME.format(when);
    result.put("order_id", orderId);
    result.put("status", "pay");
    result.put("status_desc", "已付款");
    result.put("add_time", text);
    result.put("pay_time", text);
    result.put("total", total);
    result.put("price", total);
    result.put("express_fee_num", freight);
    ObjectNode buyer = (ObjectNode) result.get("buyer_info");
    buyer.put("buyer_id", "t79-buyer");
    ArrayNode items = (ArrayNode) result.get("items");
    ObjectNode line = (ObjectNode) items.get(0);
    items.removeAll();
    items.add(line(line, itemA, "t79-a", payA, "1"));
    items.add(line(line, itemB, "t79-b", payB, "2"));
    return root.toString();
  }

  /** Refund list sample {@code refund-list-10582-v2.0.json}, emptied so sync does not page. */
  static String emptyRefundList() {
    ObjectNode root = load("/weidian/refund-list-10582-v2.0.json");
    ObjectNode result = (ObjectNode) root.get("result");
    result.put("totalNum", 0);
    result.set("list", JSON.createArrayNode());
    return root.toString();
  }

  /**
   * Settlement sample {@code trade-detail-10652-v1.0.json}. Amounts stay minor units (fen, task 76
   * {@code AMOUNT_IN_MINOR_UNITS}), which that sample already uses. {@code tradeTime} is epoch
   * milliseconds.
   */
  static String tradeDetail(Flow... rows) {
    ObjectNode root = load("/weidian/trade-detail-10652-v1.0.json");
    ObjectNode result = (ObjectNode) root.get("result");
    ArrayNode list = JSON.createArrayNode();
    for (Flow row : rows) {
      ObjectNode node = JSON.createObjectNode();
      node.put("amount", row.amountFen());
      node.put("orderId", row.orderId());
      node.put("refundId", row.refundId() == null ? "" : row.refundId());
      node.put("tradeType", row.tradeType());
      node.put("tradeSubType", row.tradeSubType());
      node.put("tradeTime", row.tradeTime().toEpochMilli());
      node.put("remark", "");
      list.add(node);
    }
    result.set("list", list);
    result.put("total", rows.length);
    return root.toString();
  }

  static String emptyTradeDetail() {
    return tradeDetail();
  }

  static String error(int statusCode) {
    return "{\"status\":{\"status_code\":" + statusCode + ",\"status_reason\":\"chain\"}}";
  }

  private static ObjectNode line(
      ObjectNode template, String itemId, String name, String pay, String subOrderId) {
    ObjectNode copy = template.deepCopy();
    copy.put("item_id", itemId);
    copy.put("cur_shop_item_id", itemId);
    copy.put("item_name", name);
    copy.put("price", pay);
    copy.put("total_price", pay);
    copy.put("quantity", 1);
    copy.put("sub_order_id", subOrderId);
    return copy;
  }

  private static ObjectNode load(String resource) {
    try (InputStream in = ChainSamples.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing Weidian sample " + resource);
      }
      JsonNode tree = JSON.readTree(in.readAllBytes());
      return (ObjectNode) tree;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  record Flow(
      String orderId,
      String refundId,
      String tradeType,
      String tradeSubType,
      int amountFen,
      Instant tradeTime) {}
}
