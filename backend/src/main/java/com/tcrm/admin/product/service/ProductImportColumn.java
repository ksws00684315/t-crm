package com.tcrm.admin.product.service;

import java.util.Arrays;
import java.util.List;

/**
 * Original-product import template columns (task 05 §4.6); header message keys resolve per language
 * in messages*.properties.
 */
public enum ProductImportColumn {
  CODE("import.product.header.code"),
  SHOP_ID("import.product.header.shop-id"),
  WEIDIAN_ITEM_ID("import.product.header.weidian-item-id"),
  NAME("import.product.header.name"),
  CATEGORY("import.product.header.category"),
  IMAGE_URL("import.product.header.image-url"),
  PRICE("import.product.header.price"),
  RATE("import.product.header.rate");

  private static final List<String> HEADER_KEYS =
      Arrays.stream(values()).map(ProductImportColumn::headerKey).toList();
  private final String headerKey;

  ProductImportColumn(String headerKey) {
    this.headerKey = headerKey;
  }

  public String headerKey() {
    return headerKey;
  }

  public String of(List<String> row) {
    return row.get(ordinal());
  }

  public static List<String> headerKeys() {
    return HEADER_KEYS;
  }
}
