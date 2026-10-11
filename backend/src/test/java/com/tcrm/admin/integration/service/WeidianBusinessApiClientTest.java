package com.tcrm.admin.integration.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WeidianBusinessApiClientTest {
  private final WeidianClient client = mock(WeidianClient.class);
  private final WeidianBusinessApiClient api =
      new WeidianBusinessApiClient(client, new WeidianBusinessApiParser());

  @Test
  void shouldUseDocumentedMethodAndVersionForEveryBusinessApi() {
    var result =
        JsonMapper.builder()
            .build()
            .readTree("{\"result\":{\"result\":{}},\"orders\":[],\"items\":[],\"list\":[]}");
    when(client.call(anyLong(), anyString(), anyString(), nullable(Object.class)))
        .thenReturn(result);

    api.orderList(
        1,
        new WeidianBusinessApi.OrderListRequest(
            null, true, null, null, 1, null, null, null, null, null, null));
    api.orderDetail(1, new WeidianBusinessApi.OrderDetailRequest(3L));
    api.refundList(
        1,
        new WeidianBusinessApi.RefundListRequest(
            null, null, null, null, null, 1, 1, List.of(), List.of(), null));
    api.refundDetail(1, new WeidianBusinessApi.RefundDetailRequest(3L));
    api.itemList(1, new WeidianBusinessApi.ItemListRequest(null, "1", null, null, null, "1"));
    api.item(1, new WeidianBusinessApi.ItemRequest("3", null, null));
    api.itemDetail(1, new WeidianBusinessApi.ItemDetailRequest(3L));
    api.categoryList(1, new WeidianBusinessApi.CategoryListRequest(1));
    api.addItem(1, null);
    api.saveItemDetail(1, null);
    api.itemOnSale(1, new WeidianBusinessApi.ItemOnSaleRequest("3", "onsale"));
    api.deleteItem(1, new WeidianBusinessApi.DeleteItemRequest("3"));

    verify(client)
        .call(
            1,
            "vdian.order.list.get",
            "1.6",
            new WeidianBusinessApi.OrderListRequest(
                null, true, null, null, 1, null, null, null, null, null, null));
    verify(client).call(1, "vdian.order.get", "2.0", new WeidianBusinessApi.OrderDetailRequest(3L));
    verify(client).call(eq(1L), eq("open.SellerQueryRefundList"), eq("2.0"), any());
    verify(client).call(eq(1L), eq("open.sellerQueryRefundDetail"), eq("2.0"), any());
    verify(client).call(eq(1L), eq("vdian.item.list.get"), eq("1.1"), any());
    verify(client).call(eq(1L), eq("vdian.item.get"), eq("1.1"), any());
    verify(client).call(eq(1L), eq("vdian.item.getItemDetail"), eq("1.0"), any());
    verify(client).call(eq(1L), eq("weidian.cate.get.list"), eq("1.0"), any());
    verify(client).call(eq(1L), eq("vdian.item.add"), eq("1.5"), isNull());
    verify(client).call(eq(1L), eq("vdian.item.saveItemDetail"), eq("1.0"), isNull());
    verify(client).call(eq(1L), eq("weidian.item.onSale"), eq("1.0"), any());
    verify(client).call(eq(1L), eq("vdian.item.delete"), eq("1.0"), any());
  }
}
