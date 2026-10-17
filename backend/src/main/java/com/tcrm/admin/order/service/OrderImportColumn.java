package com.tcrm.admin.order.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Order import template columns (task 18 §4.2); header message keys resolve per language in
 * messages*.properties.
 */
public enum OrderImportColumn {
  SHOP_ID("import.order.header.shop-id"),
  ORDER_NO("import.order.header.order-no"),
  SUB_ORDER_NO("import.order.header.sub-order-no"),
  STATUS("import.order.header.status"),
  ORDER_TIME("import.order.header.order-time"),
  PAY_TIME("import.order.header.pay-time"),
  PAY_AMOUNT("import.order.header.pay-amount"),
  FREIGHT("import.order.header.freight"),
  ITEM_ID("import.order.header.item-id"),
  SKU_ID("import.order.header.sku-id"),
  ITEM_NAME("import.order.header.item-name"),
  SKU_NAME("import.order.header.sku-name"),
  QUANTITY("import.order.header.quantity"),
  ITEM_PAY_AMOUNT("import.order.header.item-pay-amount");

  private static final List<String> HEADER_KEYS =
      Arrays.stream(values()).map(OrderImportColumn::headerKey).toList();
  private final String headerKey;

  OrderImportColumn(String headerKey) {
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

  /** 0-based columns that must stay text in the downloadable template. */
  public static Set<Integer> textColumnIndexes() {
    return Set.of(
        SHOP_ID.ordinal(),
        ORDER_NO.ordinal(),
        SUB_ORDER_NO.ordinal(),
        ITEM_ID.ordinal(),
        SKU_ID.ordinal());
  }
}
