package com.tcrm.admin.product.service;

import java.util.Arrays;
import java.util.List;

/** Kol item mapping import template columns (task 05 §4.6). */
public enum MappingImportColumn {
  SHOP_ID("店铺 ID"),
  WEIDIAN_ITEM_ID("专属商品 ID"),
  KOL_CODE("达人编号"),
  PRODUCT_CODE("原商品编码");

  private static final List<String> HEADERS =
      Arrays.stream(values()).map(MappingImportColumn::header).toList();
  private final String header;

  MappingImportColumn(String header) {
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
