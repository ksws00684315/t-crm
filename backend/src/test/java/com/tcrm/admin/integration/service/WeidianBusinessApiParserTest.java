package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response fixtures are based on the official Weidian examples listed in each fixture name. */
class WeidianBusinessApiParserTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final WeidianBusinessApiParser parser = new WeidianBusinessApiParser();

  @BeforeEach
  void assertOfficialExampleFixturesExist() {
    assertThat(getClass().getResource("/weidian/order-list-10668-v1.6.json")).isNotNull();
  }

  @Test
  void shouldParseOrderListOfficialExample_10668_v1_6() throws IOException {
    JsonNode result = result("order-list-10668-v1.6.json");

    var parsed = parser.orderList(result);

    assertThat(parsed.orderNum()).isEqualTo(1);
    assertThat(parsed.orders().getFirst().total()).isEqualByComparingTo("41.0");
    assertThat(parsed.orders().getFirst().createdAt())
        .isEqualTo(Instant.parse("2022-10-19T04:35:20Z"));
    assertThat(parsed.orders().getFirst().raw().get("buyer_info")).isNotNull();
  }

  @Test
  void shouldParseOrderDetailOfficialExample_1064_v2_0() throws IOException {
    var parsed = parser.orderDetail(result("order-detail-1064-v2.0.json"));

    assertThat(parsed.orderId()).isEqualTo("810223180971712");
    assertThat(parsed.total()).isEqualByComparingTo("41.00");
    assertThat(parsed.items()).hasSize(1);
    assertThat(parsed.raw().get("fans1_id")).isNotNull();
    assertThat(parsed.raw().get("buyer_info")).isNotNull();
  }

  @Test
  void shouldParseRefundListOfficialExample_10582_v2_0() throws IOException {
    var parsed = parser.refundList(result("refund-list-10582-v2.0.json"));

    assertThat(parsed.totalNum()).isEqualTo(1);
    assertThat(parsed.list().getFirst().refundFee()).isEqualByComparingTo("23.00");
    assertThat(parsed.list().getFirst().createdAt())
        .isEqualTo(Instant.ofEpochSecond(1_750_147_720L));
  }

  @Test
  void shouldParseRefundDetailOfficialExample_10583_v2_0() throws IOException {
    var parsed = parser.refundDetail(result("refund-detail-10583-v2.0.json"));

    assertThat(parsed.refundFeeFen()).isEqualByComparingTo(new BigDecimal("2300"));
    assertThat(parsed.createdAt()).isEqualTo(Instant.ofEpochMilli(1_750_147_720_000L));
    assertThat(parsed.raw().get("refundBasicInfo").get("buyerId")).isNotNull();
  }

  @Test
  void shouldParseItemListOfficialExample_10550_v1_1() throws IOException {
    var parsed = parser.itemList(result("item-list-10550-v1.1.json"));

    assertThat(parsed.totalNum()).isEqualTo(2);
    assertThat(parsed.items().getFirst().price()).isEqualByComparingTo("0.02");
    assertThat(parsed.items().getFirst().createdAt())
        .isEqualTo(Instant.parse("2024-04-24T07:04:15Z"));
  }

  @Test
  void shouldParseItemOfficialExample_10552_v1_1() throws IOException {
    var parsed = parser.item(result("item-10552-v1.1.json"));

    assertThat(parsed.itemId()).isEqualTo("7244889828");
    assertThat(parsed.price()).isEqualByComparingTo("99.00");
    assertThat(parsed.status()).isEqualTo("onsale");
  }

  @Test
  void shouldParseItemDetailOfficialExample_174_v1_0() throws IOException {
    var parsed = parser.itemDetail(result("item-detail-174-v1.0.json"));

    assertThat(parsed.detailContent()).hasSize(1);
    assertThat(parsed.detailContent().getFirst().position()).isEqualTo(1);
  }

  @Test
  void shouldParseItemDetailBlocks_whenResultIsBusinessNode() throws IOException {
    var parsed =
        parser.itemDetail(
            json.readTree(
                "{\"detail_content\":[{\"type\":2,\"url\":\"https://example.test/a.jpg\","
                    + "\"text\":\"图\",\"pos\":1}]}"));

    assertThat(parsed.detailContent()).hasSize(1);
    assertThat(parsed.detailContent().getFirst().url()).isEqualTo("https://example.test/a.jpg");
    assertThat(parsed.detailContent().getFirst().position()).isEqualTo(1);
  }

  @Test
  void shouldParseCategoryListOfficialExample_94_v1_0() throws IOException {
    var parsed = parser.categoryList(result("category-list-94-v1.0.json"));

    assertThat(parsed.categories()).hasSize(1);
    assertThat(parsed.categories().getFirst().itemCount()).isEqualTo(3);
    assertThat(parsed.categories().getFirst().categoryId()).isEqualTo("57493230");
  }

  @Test
  void shouldParseAddItemOfficialExample_231_v1_5() throws IOException {
    var parsed = parser.addItem(result("add-item-231-v1.5.json"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
    assertThat(parsed.addSkuIds()).isEmpty();
  }

  @Test
  void shouldParseAddItemWithItemidField_whenWeidianReturnsItemidNumber() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"add_sku_ids\":[],\"itemid\":2234677858}"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
  }

  @Test
  void shouldParseAddItemWithItemidField_whenWeidianReturnsNumericString() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"add_sku_ids\":[],\"itemid\":\"2234677858\"}"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
  }

  @Test
  void shouldParseAddItemId_whenResultItselfIsNumber() throws IOException {
    var parsed = parser.addItem(json.readTree("2234677858"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
    assertThat(parsed.addSkuIds()).isEmpty();
  }

  @Test
  void shouldParseAddItemId_whenResultItselfIsNumericString() throws IOException {
    var parsed = parser.addItem(json.readTree("\"2234677858\""));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
  }

  @Test
  void shouldParseAddItemId_whenInnerResultIsNumber() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"result\":2234677858}"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
    assertThat(parsed.addSkuIds()).isEmpty();
  }

  @Test
  void shouldParseAddItemId_whenInnerResultIsNumericString() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"result\":\"2234677858\"}"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
  }

  @Test
  void shouldParseAddItemId_whenInnerResultIsBusinessObject() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"result\":{\"itemid\":2234677858}}"));

    assertThat(parsed.itemId()).isEqualTo(2_234_677_858L);
  }

  @Test
  void shouldReturnNullItemId_whenNoKnownIdFieldPresent() throws IOException {
    var parsed = parser.addItem(json.readTree("{\"add_sku_ids\":[],\"other_id\":1}"));

    assertThat(parsed.itemId()).isNull();
  }

  @Test
  void shouldParseSaveItemDetailOfficialExample_175_v1_0() throws IOException {
    var parsed = parser.saveItemDetail(result("save-item-detail-175-v1.0.json"));

    assertThat(parsed.temporaryDetailId()).isEqualTo("id9232848421522315062671");
  }

  @Test
  void shouldParseTemporaryDetailId_whenResultIsBusinessNode() throws IOException {
    var parsed = parser.saveItemDetail(json.readTree("{\"tmp_detail_id\":\"id-42\"}"));

    assertThat(parsed.temporaryDetailId()).isEqualTo("id-42");
  }

  @Test
  void shouldParseItemOnSaleOfficialExample_52_v1_0() throws IOException {
    var response = result("item-on-sale-52-v1.0.json");

    assertThat(parser.itemOperation(response).result().asString()).isEmpty();
  }

  @Test
  void shouldParseDeleteItemOfficialExample_10541_v1_0() throws IOException {
    var response = result("delete-item-10541-v1.0.json");

    assertThat(parser.itemOperation(response).result().intValue()).isEqualTo(200);
  }

  private JsonNode result(String filename) throws IOException {
    JsonNode root = json.readTree(getClass().getResourceAsStream("/weidian/" + filename));
    return root.get("result");
  }
}
