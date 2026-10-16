package com.tcrm.admin.order.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {
  @ParameterizedTest
  @CsvSource({
    "unpay,UNPAID",
    "part_pay,UNPAID",
    "pay,PAID",
    "ship,SHIPPED",
    "split_deliver,SHIPPED",
    "accept,RECEIVED",
    "finish,COMPLETED",
    "close,CLOSED",
    "unexpected,UNKNOWN",
    "20,UNKNOWN"
  })
  void shouldNormalizeDocumentedStatuses_whenReadingOrderDetail(String raw, OrderStatus expected) {
    assertThat(OrderStatus.from(raw)).isEqualTo(expected);
  }
}
