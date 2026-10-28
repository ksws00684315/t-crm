package com.tcrm.admin.integration.service;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Parses documented Weidian response fields and leaves undocumented fields in {@code raw}. */
@Component
public class WeidianBusinessApiParser {
  private static final ZoneId WEIDIAN_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter WEIDIAN_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final List<String> ADD_ITEM_ID_FIELDS = List.of("itemid", "item_id", "itemId");

  public WeidianBusinessApi.OrderListResponse orderList(JsonNode result) {
    List<WeidianBusinessApi.OrderListItem> orders = new ArrayList<>();
    JsonNode rows = result.get("orders");
    if (rows != null && rows.isArray()) {
      rows.forEach(
          row ->
              orders.add(
                  new WeidianBusinessApi.OrderListItem(
                      text(row, "order_id"),
                      decimal(row, "total"),
                      text(row, "status"),
                      time(row, "time"),
                      time(row, "update_time"),
                      row)));
    }
    return new WeidianBusinessApi.OrderListResponse(
        integer(result, "order_num"),
        integer(result, "total_num"),
        orders,
        bool(result, "has_more"),
        text(result, "after_key"),
        result);
  }

  public WeidianBusinessApi.OrderDetailResponse orderDetail(JsonNode result) {
    List<WeidianBusinessApi.OrderLine> items = new ArrayList<>();
    JsonNode rows = result.get("items");
    if (rows != null && rows.isArray()) {
      rows.forEach(
          row ->
              items.add(
                  new WeidianBusinessApi.OrderLine(
                      text(row, "item_id"),
                      text(row, "item_name"),
                      decimal(row, "price"),
                      decimal(row, "total_price"),
                      decimal(row, "quantity"),
                      row)));
    }
    return new WeidianBusinessApi.OrderDetailResponse(
        text(result, "order_id"),
        text(result, "status"),
        text(result, "status_desc"),
        decimal(result, "total"),
        decimal(result, "price"),
        time(result, "add_time"),
        time(result, "pay_time"),
        items,
        result);
  }

  public WeidianBusinessApi.RefundListResponse refundList(JsonNode result) {
    List<WeidianBusinessApi.RefundListItem> items = new ArrayList<>();
    JsonNode rows = result.get("list");
    if (rows != null && rows.isArray()) {
      rows.forEach(
          row -> {
            JsonNode refund = row.get("refund_info");
            items.add(
                new WeidianBusinessApi.RefundListItem(
                    text(row, "refund_no"),
                    text(row, "orderID"),
                    decimal(refund, "refund_fee"),
                    epochSeconds(row, "add_time"),
                    row));
          });
    }
    return new WeidianBusinessApi.RefundListResponse(items, integer(result, "totalNum"), result);
  }

  public WeidianBusinessApi.RefundDetailResponse refundDetail(JsonNode result) {
    JsonNode basics = result.get("refundBasicInfo");
    return new WeidianBusinessApi.RefundDetailResponse(
        epochMillis(basics, "addTime"),
        epochMillis(basics, "finishTime"),
        text(basics, "refundNo"),
        text(basics, "orderId"),
        decimal(basics, "refundFee"),
        result);
  }

  /** Parses shop.order.trade.detail rows verbatim; missing fields stay null (task 76). */
  public WeidianBusinessApi.TradeDetailResponse tradeDetail(JsonNode result) {
    List<WeidianBusinessApi.TradeDetailItem> items = new ArrayList<>();
    JsonNode rows = result.get("list");
    if (rows != null && rows.isArray()) {
      rows.forEach(
          row ->
              items.add(
                  new WeidianBusinessApi.TradeDetailItem(
                      text(row, "orderId"),
                      text(row, "refundId"),
                      text(row, "tradeType"),
                      text(row, "tradeSubType"),
                      decimal(row, "amount"),
                      epochMillis(row, "tradeTime"),
                      text(row, "remark"),
                      row)));
    }
    return new WeidianBusinessApi.TradeDetailResponse(items, integer(result, "total"), result);
  }

  public WeidianBusinessApi.ItemListResponse itemList(JsonNode result) {
    List<WeidianBusinessApi.ItemSummary> items = new ArrayList<>();
    JsonNode rows = result.get("items");
    if (rows != null && rows.isArray()) {
      rows.forEach(
          row ->
              items.add(
                  new WeidianBusinessApi.ItemSummary(
                      text(row, "itemid"),
                      text(row, "item_name"),
                      text(row, "seller_id"),
                      decimal(row, "price"),
                      integer(row, "stock"),
                      integer(row, "status"),
                      time(row, "add_time"),
                      time(row, "update_time"),
                      row)));
    }
    return new WeidianBusinessApi.ItemListResponse(
        items, integer(result, "item_num"), integer(result, "total_num"), result);
  }

  public WeidianBusinessApi.ItemResponse item(JsonNode result) {
    return new WeidianBusinessApi.ItemResponse(
        text(result, "itemid"),
        text(result, "item_name"),
        text(result, "seller_id"),
        decimal(result, "price"),
        integer(result, "stock"),
        text(result, "status"),
        result);
  }

  public WeidianBusinessApi.ItemDetailResponse itemDetail(JsonNode raw) {
    JsonNode result = innerResult(raw, "detail_content");
    JsonNode blocks = result.get("detail_content");
    List<WeidianBusinessApi.ItemDetailResponseBlock> details = new ArrayList<>();
    if (blocks != null && blocks.isArray()) {
      blocks.forEach(
          block ->
              details.add(
                  new WeidianBusinessApi.ItemDetailResponseBlock(
                      integer(block, "type"),
                      text(block, "url"),
                      text(block, "text"),
                      integer(block, "pos"),
                      text(block, "faceurl"),
                      text(block, "video_type"),
                      block)));
    }
    return new WeidianBusinessApi.ItemDetailResponse(details, raw);
  }

  public WeidianBusinessApi.CategoryListResponse categoryList(JsonNode result) {
    List<WeidianBusinessApi.Category> categories = new ArrayList<>();
    if (result != null && result.isArray()) {
      result.forEach(
          row ->
              categories.add(
                  new WeidianBusinessApi.Category(
                      text(row, "cate_id"),
                      text(row, "cate_name"),
                      integer(row, "cate_item_num"),
                      integer(row, "sort_num"),
                      row)));
    }
    return new WeidianBusinessApi.CategoryListResponse(categories, result);
  }

  /**
   * Parses vdian.item.add. The {@code result} node may be the business object (the field names in
   * {@link #ADD_ITEM_ID_FIELDS}), a business object nested one level deeper, or, for some
   * interfaces, the bare item id itself (integer or numeric string) possibly nested one level
   * deeper.
   */
  public WeidianBusinessApi.AddItemResponse addItem(JsonNode raw) {
    JsonNode result = innerResult(raw, "add_sku_ids", "itemid", "item_id", "itemId");
    List<Long> skuIds = new ArrayList<>();
    JsonNode ids = result.get("add_sku_ids");
    if (ids != null && ids.isArray()) {
      ids.forEach(id -> skuIds.add(id.longValue()));
    }
    Long itemId = result.isValueNode() ? numericValue(result) : addItemId(result);
    return new WeidianBusinessApi.AddItemResponse(itemId, skuIds, raw);
  }

  /**
   * Unwraps at most one level: when {@code node} is an object that holds none of the expected
   * business fields but has a {@code result} child, that child is the business value. Otherwise the
   * node is returned unchanged.
   */
  private static JsonNode innerResult(JsonNode node, String... businessFields) {
    if (node == null || !node.isObject()) {
      return node;
    }
    for (String field : businessFields) {
      if (node.get(field) != null) {
        return node;
      }
    }
    JsonNode inner = node.get("result");
    return inner == null ? node : inner;
  }

  /**
   * Weidian's returned item id field name is not consistent across docs and live responses (task
   * 65), so the known variants are tried in order.
   */
  private static Long addItemId(JsonNode result) {
    for (String field : ADD_ITEM_ID_FIELDS) {
      Long id = numericValue(result.get(field));
      if (id != null) {
        return id;
      }
    }
    return null;
  }

  /** Reads an integral number or a numeric string; any other value yields {@code null}. */
  private static Long numericValue(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isIntegralNumber()) {
      return value.longValue();
    }
    if (value.isTextual()) {
      try {
        return Long.valueOf(value.asString().strip());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  public WeidianBusinessApi.SaveItemDetailResponse saveItemDetail(JsonNode raw) {
    JsonNode result = innerResult(raw, "tmp_detail_id");
    return new WeidianBusinessApi.SaveItemDetailResponse(text(result, "tmp_detail_id"), raw);
  }

  public WeidianBusinessApi.ItemOperationResponse itemOperation(JsonNode result) {
    return new WeidianBusinessApi.ItemOperationResponse(result, result);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static Integer integer(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.intValue();
  }

  private static Long longValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.longValue();
  }

  private static Boolean bool(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.booleanValue();
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    String value = text(node, field);
    if (value != null) {
      try {
        return new BigDecimal(value);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    JsonNode number = node == null ? null : node.get(field);
    return number == null || !number.isNumber() ? null : number.decimalValue();
  }

  private static Instant time(JsonNode node, String field) {
    String value = text(node, field);
    return value == null || value.isBlank()
        ? null
        : LocalDateTime.parse(value, WEIDIAN_TIME).atZone(WEIDIAN_ZONE).toInstant();
  }

  private static Instant epochSeconds(JsonNode node, String field) {
    String value = text(node, field);
    return value == null ? null : Instant.ofEpochSecond(Long.parseLong(value));
  }

  private static Instant epochMillis(JsonNode node, String field) {
    Long value = longValue(node, field);
    return value == null ? null : Instant.ofEpochMilli(value);
  }
}
