package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serial test: real {@link WeidianClient} (envelope stripped by {@link WeidianResponseParser})
 * feeding {@link WeidianBusinessApiClient} and {@link WeidianBusinessApiParser}. Only the token,
 * rate limiter, HTTP gateway and retry delay are stubbed, so no network is touched.
 */
class WeidianBusinessApiEnvelopeTest {
  private static final long SHOP_ID = 1L;
  private static final String ACCESS_TOKEN = "access-token";

  private final JsonMapper json = JsonMapper.builder().build();
  private final WeidianTokenCenter tokens = mock(WeidianTokenCenter.class);
  private final WeidianRateLimiter limiter = mock(WeidianRateLimiter.class);
  private final WeidianHttpGateway gateway = mock(WeidianHttpGateway.class);
  private final WeidianRetryDelay delay = mock(WeidianRetryDelay.class);
  private final WeidianBusinessApi api =
      new WeidianBusinessApiClient(
          new WeidianClient(tokens, limiter, gateway, delay), new WeidianBusinessApiParser());

  @BeforeEach
  void authorizedShop() {
    when(tokens.accessToken(SHOP_ID)).thenReturn(new WeidianToken("app-key", ACCESS_TOKEN));
  }

  @Test
  void shouldParseOrderList_whenWeidianReturnsEnvelope() {
    respond(
        "vdian.order.list.get",
        "1.6",
        "{\"order_num\":1,\"total_num\":3,\"has_more\":true,\"after_key\":\"ak-1\","
            + "\"orders\":[{\"order_id\":\"810223180971712\",\"total\":\"41.00\","
            + "\"status\":\"20\",\"time\":\"2026-01-02 03:04:05\"}]}");

    var parsed =
        api.orderList(
            SHOP_ID,
            new WeidianBusinessApi.OrderListRequest(
                null, true, null, null, 1, null, null, null, null, null, null));

    assertThat(parsed.orderNum()).isEqualTo(1);
    assertThat(parsed.totalNum()).isEqualTo(3);
    assertThat(parsed.nextAfterKey()).isEqualTo("ak-1");
    assertThat(parsed.orders().getFirst().orderId()).isEqualTo("810223180971712");
    assertThat(parsed.orders().getFirst().total()).isEqualByComparingTo("41.00");
  }

  @Test
  void shouldParseOrderDetail_whenWeidianReturnsEnvelope() {
    respond(
        "vdian.order.get",
        "2.0",
        "{\"order_id\":\"810223180971712\",\"status\":\"20\",\"total\":\"41.00\","
            + "\"items\":[{\"item_id\":\"2531259234\",\"item_name\":\"Item\","
            + "\"price\":\"41.00\",\"total_price\":\"41.00\",\"quantity\":1}]}");

    var parsed =
        api.orderDetail(SHOP_ID, new WeidianBusinessApi.OrderDetailRequest(810223180971712L));

    assertThat(parsed.orderId()).isEqualTo("810223180971712");
    assertThat(parsed.total()).isEqualByComparingTo("41.00");
    assertThat(parsed.items().getFirst().itemId()).isEqualTo("2531259234");
    assertThat(parsed.items().getFirst().totalPrice()).isEqualByComparingTo("41.00");
  }

  @Test
  void shouldParseRefundList_whenWeidianReturnsEnvelope() {
    respond(
        "open.SellerQueryRefundList",
        "2.0",
        "{\"list\":[{\"refund_no\":\"1001\",\"orderID\":\"810\","
            + "\"refund_info\":{\"refund_fee\":\"23.00\"},\"add_time\":\"1750147720\"}],"
            + "\"totalNum\":1}");

    var parsed =
        api.refundList(
            SHOP_ID,
            new WeidianBusinessApi.RefundListRequest(
                null, null, null, null, null, 1, 1, List.of(), List.of(), null));

    assertThat(parsed.totalNum()).isEqualTo(1);
    assertThat(parsed.list().getFirst().refundNo()).isEqualTo("1001");
    assertThat(parsed.list().getFirst().refundFee()).isEqualByComparingTo("23.00");
  }

  @Test
  void shouldParseRefundDetail_whenWeidianReturnsEnvelope() {
    respond(
        "open.sellerQueryRefundDetail",
        "2.0",
        "{\"refundBasicInfo\":{\"refundNo\":\"1001\",\"orderId\":\"810\","
            + "\"refundFee\":\"2300\",\"addTime\":1750147720000,\"finishTime\":1750147800000}}");

    var parsed = api.refundDetail(SHOP_ID, new WeidianBusinessApi.RefundDetailRequest(1001L));

    assertThat(parsed.refundNo()).isEqualTo("1001");
    assertThat(parsed.orderId()).isEqualTo("810");
    assertThat(parsed.refundFeeFen()).isEqualByComparingTo(new BigDecimal("2300"));
    assertThat(parsed.createdAt()).isEqualTo(Instant.ofEpochMilli(1_750_147_720_000L));
  }

  @Test
  void shouldParseItemList_whenWeidianReturnsEnvelope() {
    respond(
        "vdian.item.list.get",
        "1.1",
        "{\"items\":[{\"itemid\":\"7244889828\",\"item_name\":\"Item\",\"seller_id\":\"1\","
            + "\"price\":\"0.02\",\"stock\":3,\"status\":1,\"add_time\":\"2024-04-24 15:04:15\"}],"
            + "\"item_num\":1,\"total_num\":2}");

    var parsed =
        api.itemList(
            SHOP_ID, new WeidianBusinessApi.ItemListRequest(null, "1", null, null, null, "1"));

    assertThat(parsed.totalNum()).isEqualTo(2);
    assertThat(parsed.items().getFirst().itemId()).isEqualTo("7244889828");
    assertThat(parsed.items().getFirst().createdAt())
        .isEqualTo(Instant.parse("2024-04-24T07:04:15Z"));
  }

  @Test
  void shouldParseItem_whenWeidianReturnsEnvelope() {
    respond(
        "vdian.item.get",
        "1.1",
        "{\"itemid\":\"7244889828\",\"item_name\":\"Item\",\"seller_id\":\"1\","
            + "\"price\":\"99.00\",\"stock\":5,\"status\":\"onsale\"}");

    var parsed = api.item(SHOP_ID, new WeidianBusinessApi.ItemRequest("7244889828", null, null));

    assertThat(parsed.itemId()).isEqualTo("7244889828");
    assertThat(parsed.price()).isEqualByComparingTo("99.00");
    assertThat(parsed.status()).isEqualTo("onsale");
  }

  @Test
  void shouldParseItemDetailBlocks_whenWeidianReturnsEnvelope() {
    respond(
        "vdian.item.getItemDetail",
        "1.0",
        "{\"detail_content\":[{\"type\":2,\"url\":\"https://example.test/a.jpg\","
            + "\"text\":\"img\",\"pos\":1,\"faceurl\":\"https://example.test/a.jpg\","
            + "\"video_type\":\"1\"}]}");

    var parsed = api.itemDetail(SHOP_ID, new WeidianBusinessApi.ItemDetailRequest(7244889828L));

    assertThat(parsed.detailContent()).hasSize(1);
    assertThat(parsed.detailContent().getFirst().url()).isEqualTo("https://example.test/a.jpg");
    assertThat(parsed.detailContent().getFirst().position()).isEqualTo(1);
  }

  @Test
  void shouldParseCategories_whenWeidianReturnsEnvelope() {
    respond(
        "weidian.cate.get.list",
        "1.0",
        "[{\"cate_id\":\"57493230\",\"cate_name\":\"Default\",\"cate_item_num\":3,"
            + "\"sort_num\":1}]");

    var parsed = api.categoryList(SHOP_ID, new WeidianBusinessApi.CategoryListRequest(1));

    assertThat(parsed.categories()).hasSize(1);
    assertThat(parsed.categories().getFirst().categoryId()).isEqualTo("57493230");
    assertThat(parsed.categories().getFirst().itemCount()).isEqualTo(3);
  }

  @Test
  void shouldParseNewItemId_whenWeidianReturnsEnvelope() {
    respond("vdian.item.add", "1.5", "{\"itemid\":2234677858,\"add_sku_ids\":[]}");

    var parsed = api.addItem(SHOP_ID, null);

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
    assertThat(parsed.addSkuIds()).isEmpty();
  }

  @Test
  void shouldParseNewItemId_whenWeidianNestsScalarIdInResult() {
    respond("vdian.item.add", "1.5", "{\"result\":12345}");

    var parsed = api.addItem(SHOP_ID, null);

    assertThat(parsed.itemId()).isEqualTo(12_345L);
  }

  @Test
  void shouldParseTemporaryDetailId_whenWeidianReturnsEnvelope() {
    respond("vdian.item.saveItemDetail", "1.0", "{\"tmp_detail_id\":\"id9232848421522315062671\"}");

    var parsed = api.saveItemDetail(SHOP_ID, null);

    assertThat(parsed.temporaryDetailId()).isEqualTo("id9232848421522315062671");
  }

  @Test
  void shouldReturnBusinessResult_whenOnSaleReturnsEnvelope() {
    respond("weidian.item.onSale", "1.0", "\"\"");

    var response =
        api.itemOnSale(SHOP_ID, new WeidianBusinessApi.ItemOnSaleRequest("7244889828", "onsale"));

    assertThat(response.result().isTextual()).isTrue();
    assertThat(response.result().asString()).isEmpty();
  }

  @Test
  void shouldReturnBusinessResult_whenDeleteReturnsEnvelope() {
    respond("vdian.item.delete", "1.0", "200");

    var response = api.deleteItem(SHOP_ID, new WeidianBusinessApi.DeleteItemRequest("7244889828"));

    assertThat(response.result().intValue()).isEqualTo(200);
  }

  private void respond(String method, String version, String resultJson) {
    JsonNode envelope =
        json.readTree(
            "{\"status\":{\"status_code\":0,\"status_reason\":\"success\"},\"result\":"
                + resultJson
                + "}");
    when(gateway.apiPost(eq(method), eq(version), eq(ACCESS_TOKEN), anyString()))
        .thenReturn(envelope);
  }
}
