package com.tcrm.admin.order.service;

import java.util.Arrays;
import java.util.List;

/**
 * Refund import template columns (task 18 §4.3); header message keys resolve per language in
 * messages*.properties.
 */
public enum RefundImportColumn {
  SHOP_ID("import.refund.header.shop-id"),
  REFUND_NO("import.refund.header.refund-no"),
  ORDER_NO("import.refund.header.order-no"),
  SUB_ORDER_NO("import.refund.header.sub-order-no"),
  AMOUNT("import.refund.header.amount"),
  REFUND_TIME("import.refund.header.refund-time"),
  FINISH_TIME("import.refund.header.finish-time");

  private static final List<String> HEADER_KEYS =
      Arrays.stream(values()).map(RefundImportColumn::headerKey).toList();
  private final String headerKey;

  RefundImportColumn(String headerKey) {
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
