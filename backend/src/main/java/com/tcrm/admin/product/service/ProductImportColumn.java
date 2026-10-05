package com.tcrm.admin.product.service;

import java.util.Arrays;
import java.util.List;

/** Original-product import template columns (task 05 §4.6). */
public enum ProductImportColumn {
  CODE("原商品编码"),
  SHOP_ID("店铺 ID"),
  WEIDIAN_ITEM_ID("微店商品 ID"),
  NAME("商品名称"),
  CATEGORY("分类"),
  IMAGE_URL("图片地址"),
  PRICE("价格"),
  RATE("商品佣金比例(%)");

  private static final List<String> HEADERS =
      Arrays.stream(values()).map(ProductImportColumn::header).toList();
  private final String header;

  ProductImportColumn(String header) {
    this.header = header;
  }

  public String header() {
    return header;
  }

  public String of(List<String> row) {
    return row.get(ordinal());
  }

  public static List<String> headers() {
    return HEADERS;
  }
}
