package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.product.api.SheetCellDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SheetLinkParserTest {
  private static final String KAKOBUY =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop1856156775.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D7736025747&affcode=abc";

  @Test
  void shouldPreferCellLink_thenRunLink_thenFormula_thenText() {
    assertThat(
            SheetLinkParser.link(
                new SheetCellDTO(
                    "LINK",
                    "https://a.test/1",
                    "=HYPERLINK(\"https://b.test\")",
                    List.of("https://c.test"))))
        .isEqualTo("https://a.test/1");
    assertThat(
            SheetLinkParser.link(
                new SheetCellDTO("LINK", null, null, List.of("https://c.test", "https://d.test"))))
        .isEqualTo("https://c.test");
    assertThat(
            SheetLinkParser.link(
                new SheetCellDTO(
                    "LINK", null, "=hyperlink( \"https://b.test/x\", \"LINK\")", List.of())))
        .isEqualTo("https://b.test/x");
    assertThat(SheetLinkParser.link(new SheetCellDTO(" https://e.test ", null, null, List.of())))
        .isEqualTo("https://e.test");
  }

  @Test
  void shouldReturnNoLink_whenCellIsTextOrMissing() {
    assertThat(SheetLinkParser.link(null)).isNull();
    assertThat(SheetLinkParser.link(new SheetCellDTO("Tees/Vest", null, null, List.of()))).isNull();
  }

  @Test
  void shouldTakeItemAndShopFromAnEncodedAgentLink() {
    String decoded = SheetLinkParser.decode(KAKOBUY);

    assertThat(SheetLinkParser.isOtherPlatform(decoded)).isFalse();
    assertThat(SheetLinkParser.itemId(decoded)).isEqualTo("7736025747");
    assertThat(SheetLinkParser.shopId(decoded)).isEqualTo("1856156775");
  }

  @Test
  void shouldDecodeUntilStable_whenTheWeidianUrlIsEncodedTwice() {
    String twice =
        "https://agent.test/?u=https%253A%252F%252Fweidian.com%252Fitem.html%253FitemID%253D7739078620";

    assertThat(SheetLinkParser.itemId(SheetLinkParser.decode(twice))).isEqualTo("7739078620");
  }

  @ParameterizedTest
  @CsvSource({
    "https://litbuy.com/product/weidian/7736096515?inviteCode=CNMASTAIR, 7736096515",
    "https://weidian.com/item.html?itemID=7738954652, 7738954652",
    "https://weidian.com/item.html?ItemId=12345, 12345",
    "https://agent.test/p/7735954741/detail, 7735954741"
  })
  void shouldFindTheItemId(String url, String expected) {
    assertThat(SheetLinkParser.itemId(SheetLinkParser.decode(url))).isEqualTo(expected);
  }

  @Test
  void shouldNotMistakeTheShopIdForTheItemId_whenTheLinkHasNoItem() {
    assertThat(SheetLinkParser.itemId("https://shop1856156775.v.weidian.com/")).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "https://item.taobao.com/item.htm?id=712345678901, true",
    "https://detail.1688.com/offer/712345678901.html, true",
    "https://www.kakobuy.com/item/details?url=https://detail.tmall.com/item.htm?id=1, true",
    "https://litbuy.com/product/taobao/712345678901, true",
    // Digits "1688" inside a Weidian item ID are not a platform.
    "https://weidian.com/item.html?itemID=7716882345, false",
    "https://agent.test/p/7716882345, false"
  })
  void shouldRecogniseOtherPlatforms(String url, boolean other) {
    assertThat(SheetLinkParser.isOtherPlatform(SheetLinkParser.decode(url))).isEqualTo(other);
  }

  @Test
  void shouldReadImageFormula_only() {
    assertThat(
            SheetLinkParser.image(
                new SheetCellDTO("", null, "=IMAGE(\"https://img.test/a.jpg\")", List.of())))
        .isEqualTo("https://img.test/a.jpg");
    assertThat(
            SheetLinkParser.image(
                new SheetCellDTO("", null, "=IMAGE(\"http://img.test/a.jpg\")", List.of())))
        .isEmpty();
    assertThat(SheetLinkParser.image(new SheetCellDTO("photo", null, null, List.of()))).isEmpty();
    assertThat(SheetLinkParser.image(null)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "$60; USD; 60",
        "¥145 | $21.13 | €18.35 | £15.97; USD; 21.13",
        "¥145 | $21.13 | €18.35 | £15.97; EUR; 18.35",
        "$ 1,234.50; USD; 1234.50",
        "USD 99; USD; 99",
        "约 88 元; CNY; 88",
        "12; JPY; 12",
        "sold out; USD; ''"
      })
  void shouldPickThePriceInTheConfiguredCurrency(String text, String currency, String expected) {
    assertThat(SheetLinkParser.price(text, currency)).isEqualTo(expected);
  }

  @Test
  void shouldReturnEmptyPrice_whenBlank() {
    assertThat(SheetLinkParser.price(" ", "USD")).isEmpty();
    assertThat(SheetLinkParser.price(null, "USD")).isEmpty();
  }
}
