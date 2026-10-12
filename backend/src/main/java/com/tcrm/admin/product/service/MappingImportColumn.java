package com.tcrm.admin.product.service;

import java.util.Arrays;
import java.util.List;

/**
 * Kol item mapping import template columns (task 05 §4.6); header message keys resolve per language
 * in messages*.properties.
 */
public enum MappingImportColumn {
  SHOP_ID("import.mapping.header.shop-id"),
  WEIDIAN_ITEM_ID("import.mapping.header.exclusive-item-id"),
  KOL_CODE("import.mapping.header.kol-code"),
  PRODUCT_CODE("import.mapping.header.product-code");

  private static final List<String> HEADER_KEYS =
      Arrays.stream(values()).map(MappingImportColumn::headerKey).toList();
  private final String headerKey;

  MappingImportColumn(String headerKey) {
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
