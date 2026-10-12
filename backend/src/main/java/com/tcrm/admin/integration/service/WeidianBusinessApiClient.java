package com.tcrm.admin.integration.service;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Executes the supported documented Weidian business APIs without adding business workflows. */
@Service
public class WeidianBusinessApiClient implements WeidianBusinessApi {
  private final WeidianClient client;
  private final WeidianBusinessApiParser parser;

  public WeidianBusinessApiClient(WeidianClient client, WeidianBusinessApiParser parser) {
    this.client = client;
    this.parser = parser;
  }

  @Override
  public OrderListResponse orderList(long shopId, OrderListRequest request) {
    return parser.orderList(call(shopId, "vdian.order.list.get", "1.6", request));
  }

  @Override
  public OrderDetailResponse orderDetail(long shopId, OrderDetailRequest request) {
    return parser.orderDetail(call(shopId, "vdian.order.get", "2.0", request));
  }

  @Override
  public RefundListResponse refundList(long shopId, RefundListRequest request) {
    return parser.refundList(call(shopId, "open.SellerQueryRefundList", "2.0", request));
  }

  @Override
  public RefundDetailResponse refundDetail(long shopId, RefundDetailRequest request) {
    return parser.refundDetail(call(shopId, "open.sellerQueryRefundDetail", "2.0", request));
  }

  @Override
  public ItemListResponse itemList(long shopId, ItemListRequest request) {
    return parser.itemList(call(shopId, "vdian.item.list.get", "1.1", request));
  }

  @Override
  public ItemResponse item(long shopId, ItemRequest request) {
    return parser.item(call(shopId, "vdian.item.get", "1.1", request));
  }

  @Override
  public ItemDetailResponse itemDetail(long shopId, ItemDetailRequest request) {
    return parser.itemDetail(call(shopId, "vdian.item.getItemDetail", "1.0", request));
  }

  @Override
  public CategoryListResponse categoryList(long shopId, CategoryListRequest request) {
    return parser.categoryList(call(shopId, "weidian.cate.get.list", "1.0", request));
  }

  @Override
  public AddItemResponse addItem(long shopId, AddItemRequest request) {
    return parser.addItem(call(shopId, "vdian.item.add", "1.5", request));
  }

  @Override
  public SaveItemDetailResponse saveItemDetail(long shopId, SaveItemDetailRequest request) {
    return parser.saveItemDetail(call(shopId, "vdian.item.saveItemDetail", "1.0", request));
  }

  @Override
  public ItemOperationResponse itemOnSale(long shopId, ItemOnSaleRequest request) {
    return parser.itemOperation(call(shopId, "weidian.item.onSale", "1.0", request));
  }

  @Override
  public ItemOperationResponse deleteItem(long shopId, DeleteItemRequest request) {
    return parser.itemOperation(call(shopId, "vdian.item.delete", "1.0", request));
  }

  private JsonNode call(long shopId, String method, String version, Object request) {
    return client.call(shopId, method, version, request);
  }
}
