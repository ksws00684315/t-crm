package com.tcrm.admin.integration.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Typed definitions for the Weidian business APIs used by later order and product tasks.
 *
 * <p>Every response's {@code raw} node is the envelope's {@code result} node (the business object,
 * with the {@code status} envelope already removed), never the full HTTP envelope.
 */
public interface WeidianBusinessApi {
  OrderListResponse orderList(long shopId, OrderListRequest request);

  OrderDetailResponse orderDetail(long shopId, OrderDetailRequest request);

  RefundListResponse refundList(long shopId, RefundListRequest request);

  RefundDetailResponse refundDetail(long shopId, RefundDetailRequest request);

  TradeDetailResponse tradeDetail(long shopId, TradeDetailRequest request);

  ItemListResponse itemList(long shopId, ItemListRequest request);

  ItemResponse item(long shopId, ItemRequest request);

  ItemDetailResponse itemDetail(long shopId, ItemDetailRequest request);

  CategoryListResponse categoryList(long shopId, CategoryListRequest request);

  AddItemResponse addItem(long shopId, AddItemRequest request);

  SaveItemDetailResponse saveItemDetail(long shopId, SaveItemDetailRequest request);

  ItemOperationResponse itemOnSale(long shopId, ItemOnSaleRequest request);

  ItemOperationResponse deleteItem(long shopId, DeleteItemRequest request);

  record OrderListRequest(
      @JsonProperty("page_num") Integer pageNum,
      @JsonProperty("use_has_more") Boolean useHasMore,
      @JsonProperty("after_key") String afterKey,
      @JsonProperty("order_type") String orderType,
      @JsonProperty("page_size") Integer pageSize,
      @JsonProperty("update_end") String updateEnd,
      @JsonProperty("add_start") String addStart,
      @JsonProperty("add_end") String addEnd,
      @JsonProperty("update_start") String updateStart,
      @JsonProperty("group_type") String groupType,
      @JsonProperty("order_biz_type") Integer orderBizType) {}

  record OrderDetailRequest(@JsonProperty("order_id") Long orderId) {}

  record RefundListRequest(
      @JsonProperty("refundStage") Integer refundStage,
      @JsonProperty("addTimeStart") String addTimeStart,
      @JsonProperty("addTimeEnd") String addTimeEnd,
      @JsonProperty("updateTimeStart") String updateTimeStart,
      @JsonProperty("updateTimeEnd") String updateTimeEnd,
      @JsonProperty("pageNum") Integer pageNum,
      @JsonProperty("pageSize") Integer pageSize,
      @JsonProperty("orderIdList") List<Long> orderIdList,
      @JsonProperty("statusList") List<Integer> statusList,
      @JsonProperty("fromRefundNo") Long fromRefundNo) {}

  record RefundDetailRequest(@JsonProperty("refundNo") Long refundNo) {}

  /**
   * shop.order.trade.detail 1.0 (settlement detail, task 76). {@code startTime}/{@code endTime} are
   * epoch milliseconds and the window must span at most about 89 days (task 75: a full three
   * calendar months is rejected); {@code pageSize} is at most 100.
   */
  record TradeDetailRequest(
      @JsonProperty("pageNo") Integer pageNo,
      @JsonProperty("pageSize") Integer pageSize,
      @JsonProperty("startTime") Long startTime,
      @JsonProperty("endTime") Long endTime,
      @JsonProperty("queryType") String queryType) {}

  record ItemListRequest(
      @JsonProperty("update_end") String updateEnd,
      @JsonProperty("page_num") String pageNum,
      @JsonProperty("update_start") String updateStart,
      @JsonProperty("status") Integer status,
      @JsonProperty("orderby") String orderby,
      @JsonProperty("page_size") String pageSize) {}

  record ItemRequest(
      @JsonProperty("itemid") String itemId,
      @JsonProperty("barcode") String barcode,
      @JsonProperty("merchantcode") String merchantCode) {
    public ItemRequest {
      if (blank(itemId) && blank(barcode) && blank(merchantCode)) {
        throw new IllegalArgumentException("one of itemid, barcode, or merchantcode is required");
      }
    }
  }

  record ItemDetailRequest(@JsonProperty("item_id") Long itemId) {}

  record CategoryListRequest(@JsonProperty("showNoCate") Integer showNoCate) {}

  record AddItemRequest(
      @JsonProperty("attrList") List<AttributeGroup> attributes,
      @JsonProperty("createTempAttr") Boolean createTempAttr,
      @JsonProperty("cateIds") String categoryIds,
      @JsonProperty("stock") Integer stock,
      @JsonProperty("crossedPrice") BigDecimal crossedPrice,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("weight") String weight,
      @JsonProperty("minPrice") BigDecimal minPrice,
      @JsonProperty("maxPrice") BigDecimal maxPrice,
      @JsonProperty("costPrice") BigDecimal costPrice,
      @JsonProperty("expressFeeTemplateId") Long expressFeeTemplateId,
      @JsonProperty("deliveryTime") DeliveryTime deliveryTime,
      @JsonProperty("freeDelivery") Boolean freeDelivery,
      @JsonProperty("remoteFreeDelivery") Boolean remoteFreeDelivery,
      @JsonProperty("imgs") List<ItemImage> images,
      @JsonProperty("itemComment") String itemComment,
      @JsonProperty("itemDeliveryList") List<Integer> deliveryTypes,
      @JsonProperty("itemDetails") List<ItemDetailBlock> details,
      @JsonProperty("itemName") String itemName,
      @JsonProperty("status") Integer status,
      @JsonProperty("merchantCode") String merchantCode,
      @JsonProperty("pluCode") String pluCode,
      @JsonProperty("shopPropertyDOS") List<ShopProperty> shopProperties,
      @JsonProperty("skus") List<ItemSku> skus) {}

  record AttributeGroup(
      @JsonProperty("attrTitle") String title,
      @JsonProperty("attrValues") List<AttributeValue> values) {}

  record AttributeValue(
      @JsonProperty("attrId") Long id,
      @JsonProperty("attrValue") String value,
      @JsonProperty("img") String image) {}

  record DeliveryTime(
      @JsonProperty("deliveryAtAfterPayBefore") Integer deliveryAtAfterPayBefore,
      @JsonProperty("deliveryTimeType") Integer deliveryTimeType,
      @JsonProperty("payBefore") Integer payBefore,
      @JsonProperty("afterPayDeliveryAt") Integer afterPayDeliveryAt) {}

  record ItemImage(@JsonProperty("title") String title, @JsonProperty("url") String url) {}

  record ItemDetailBlock(
      @JsonProperty("type") Integer type,
      @JsonProperty("text") String text,
      @JsonProperty("url") String url,
      @JsonProperty("data") String data,
      JsonNode raw) {}

  record ShopProperty(
      @JsonProperty("propertyId") Long propertyId,
      @JsonProperty("propertyValues") List<ShopPropertyValue> propertyValues) {}

  record ShopPropertyValue(
      @JsonProperty("propertyValueId") Long propertyValueId,
      @JsonProperty("selected") Integer selected) {}

  record ItemSku(
      @JsonProperty("attrIds") List<List<Long>> attributeIds,
      @JsonProperty("costPrice") BigDecimal costPrice,
      @JsonProperty("merchantCode") String merchantCode,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("minPrice") BigDecimal minPrice,
      @JsonProperty("maxPrice") BigDecimal maxPrice,
      @JsonProperty("stock") Integer stock) {}

  record SaveItemDetailRequest(
      @JsonProperty("item_detail") List<SaveItemDetailBlock> itemDetails) {}

  record SaveItemDetailBlock(
      @JsonProperty("type") Integer type,
      @JsonProperty("url") String url,
      @JsonProperty("video_type") String videoType,
      @JsonProperty("text") String text,
      @JsonProperty("faceurl") String faceUrl,
      @JsonProperty("pos") Integer position) {}

  record ItemOnSaleRequest(
      @JsonProperty("itemid") String itemId, @JsonProperty("opt") String option) {}

  record DeleteItemRequest(@JsonProperty("itemid") String itemId) {}

  record OrderListResponse(
      @JsonProperty("order_num") Integer orderNum,
      @JsonProperty("total_num") Integer totalNum,
      @JsonProperty("orders") List<OrderListItem> orders,
      @JsonProperty("has_more") Boolean hasMore,
      @JsonProperty("after_key") String nextAfterKey,
      JsonNode raw) {}

  record OrderListItem(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("total") BigDecimal total,
      @JsonProperty("status") String status,
      @JsonProperty("time") Instant createdAt,
      @JsonProperty("update_time") Instant updatedAt,
      JsonNode raw) {}

  record OrderDetailResponse(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("status") String status,
      @JsonProperty("status_desc") String statusDescription,
      @JsonProperty("total") BigDecimal total,
      @JsonProperty("item_price") BigDecimal itemPrice,
      @JsonProperty("add_time") Instant createdAt,
      @JsonProperty("pay_time") Instant paidAt,
      @JsonProperty("items") List<OrderLine> items,
      JsonNode raw) {}

  record OrderLine(
      @JsonProperty("item_id") String itemId,
      @JsonProperty("item_name") String itemName,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("total_price") BigDecimal totalPrice,
      @JsonProperty("count") BigDecimal quantity,
      JsonNode raw) {}

  record RefundListResponse(
      @JsonProperty("list") List<RefundListItem> list,
      @JsonProperty("totalNum") Integer totalNum,
      JsonNode raw) {}

  record RefundListItem(
      @JsonProperty("refund_no") String refundNo,
      @JsonProperty("orderID") String orderId,
      @JsonProperty("refund_fee") BigDecimal refundFee,
      @JsonProperty("add_time") Instant createdAt,
      JsonNode raw) {}

  record RefundDetailResponse(
      @JsonProperty("addTime") Instant createdAt,
      @JsonProperty("finishTime") Instant finishedAt,
      @JsonProperty("refundNo") String refundNo,
      @JsonProperty("orderId") String orderId,
      @JsonProperty("refundFee") BigDecimal refundFeeFen,
      JsonNode raw) {}

  /** shop.order.trade.detail result: {@code result.list} rows plus {@code result.total}. */
  record TradeDetailResponse(List<TradeDetailItem> list, Integer total, JsonNode raw) {}

  /**
   * One settlement flow row. {@code amount} is the raw value exactly as returned (no unit
   * conversion); {@code tradeTime} is epoch milliseconds. The open-platform doc records no flow id,
   * so deduplication happens on a fingerprint of these fields (task 76 §3.2).
   */
  record TradeDetailItem(
      @JsonProperty("orderId") String orderId,
      @JsonProperty("refundId") String refundId,
      @JsonProperty("tradeType") String tradeType,
      @JsonProperty("tradeSubType") String tradeSubType,
      @JsonProperty("amount") BigDecimal amount,
      @JsonProperty("tradeTime") Instant tradeTime,
      @JsonProperty("remark") String remark,
      JsonNode raw) {}

  record ItemListResponse(
      @JsonProperty("items") List<ItemSummary> items,
      @JsonProperty("item_num") Integer itemNum,
      @JsonProperty("total_num") Integer totalNum,
      JsonNode raw) {}

  record ItemSummary(
      @JsonProperty("itemid") String itemId,
      @JsonProperty("item_name") String itemName,
      @JsonProperty("seller_id") String sellerId,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("stock") Integer stock,
      @JsonProperty("status") Integer status,
      @JsonProperty("add_time") Instant createdAt,
      @JsonProperty("update_time") Instant updatedAt,
      JsonNode raw) {}

  record ItemResponse(
      @JsonProperty("itemid") String itemId,
      @JsonProperty("item_name") String itemName,
      @JsonProperty("seller_id") String sellerId,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("stock") Integer stock,
      @JsonProperty("status") String status,
      JsonNode raw) {}

  record ItemDetailResponse(
      @JsonProperty("detail_content") List<ItemDetailResponseBlock> detailContent, JsonNode raw) {}

  record ItemDetailResponseBlock(
      @JsonProperty("type") Integer type,
      @JsonProperty("url") String url,
      @JsonProperty("text") String text,
      @JsonProperty("pos") Integer position,
      @JsonProperty("faceurl") String faceUrl,
      @JsonProperty("video_type") String videoType,
      JsonNode raw) {}

  record CategoryListResponse(List<Category> categories, JsonNode raw) {}

  record Category(
      @JsonProperty("cate_id") String categoryId,
      @JsonProperty("cate_name") String categoryName,
      @JsonProperty("cate_item_num") Integer itemCount,
      @JsonProperty("sort_num") Integer sortNumber,
      JsonNode raw) {}

  record AddItemResponse(
      @JsonProperty("item_id") Long itemId,
      @JsonProperty("add_sku_ids") List<Long> addSkuIds,
      JsonNode raw) {}

  record SaveItemDetailResponse(
      @JsonProperty("tmp_detail_id") String temporaryDetailId, JsonNode raw) {}

  record ItemOperationResponse(JsonNode result, JsonNode raw) {}

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
