package com.tcrm.admin.order.service;

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
  ITEM_PAY_AMOUNT("import.order.header.item-pay-amount"),
  UNIT_PRICE("import.order.header.unit-price");

  /** Columns of the template before task 110; files with exactly these are still accepted. */
  public static final int LEGACY_COLUMNS = 14;

  private static final List<OrderImportColumn> VALUES = List.of(values());
  private static final List<String> HEADER_KEYS =
      VALUES.stream().map(OrderImportColumn::headerKey).toList();
  private static final List<String> LEGACY_HEADER_KEYS =
      VALUES.subList(0, LEGACY_COLUMNS).stream().map(OrderImportColumn::headerKey).toList();
  private final String headerKey;

  OrderImportColumn(String headerKey) {
    this.headerKey = headerKey;
  }

  public String headerKey() {
    return headerKey;
  }

  /** Cell of this column in a row given in template order; "" when the file lacks the column. */
  public String of(List<String> row) {
    return ordinal() < row.size() ? row.get(ordinal()) : "";
  }

  public static List<String> headerKeys() {
    return HEADER_KEYS;
  }

  /** Header keys of the pre-task-110 template, for the accepted legacy file variant. */
  public static List<String> legacyHeaderKeys() {
    return LEGACY_HEADER_KEYS;
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
