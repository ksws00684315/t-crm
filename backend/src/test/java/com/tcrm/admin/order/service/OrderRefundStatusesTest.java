package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Order-level refund status derivation (task 40 §3.1, row 08.02). */
class OrderRefundStatusesTest {
  private static RefundDO refund(String status, String amount) {
    var row = new RefundDO();
    row.setRefundStatus(status);
    row.setAmount(amount == null ? null : new BigDecimal(amount));
    return row;
  }

  @Test
  void shouldReturnNone_whenThereAreNoRefunds() {
    assertThat(OrderRefundStatuses.of(List.of(), new BigDecimal("10.00")))
        .isEqualTo(OrderRefundStatuses.NONE);
    assertThat(OrderRefundStatuses.of(null, new BigDecimal("10.00")))
        .isEqualTo(OrderRefundStatuses.NONE);
  }

  @Test
  void shouldReturnNone_whenNothingSucceededOrIsRunning() {
    assertThat(
            OrderRefundStatuses.of(
                List.of(refund("CLOSED", "10.00"), refund("UNKNOWN", "1.00")),
                new BigDecimal("10.00")))
        .isEqualTo(OrderRefundStatuses.NONE);
  }

  @Test
  void shouldReturnProcessing_whenAnyRefundIsProcessing_evenWithSuccesses() {
    assertThat(
            OrderRefundStatuses.of(
                List.of(refund("SUCCESS", "4.00"), refund("PROCESSING", "1.00")),
                new BigDecimal("10.00")))
        .isEqualTo(OrderRefundStatuses.PROCESSING);
  }

  @Test
  void shouldDistinguishPartialAndFullSuccess_byPaidAmount() {
    var succeeded = List.of(refund("SUCCESS", "4.00"), refund("SUCCESS", "6.00"));
    assertThat(OrderRefundStatuses.of(succeeded, new BigDecimal("10.00")))
        .isEqualTo(OrderRefundStatuses.FULL_SUCCESS);
    assertThat(OrderRefundStatuses.of(succeeded, new BigDecimal("10.01")))
        .isEqualTo(OrderRefundStatuses.PARTIAL_SUCCESS);
    // A refund larger than the paid amount still counts as full.
    assertThat(OrderRefundStatuses.of(succeeded, new BigDecimal("9.00")))
        .isEqualTo(OrderRefundStatuses.FULL_SUCCESS);
  }

  @Test
  void shouldOnlyCountSuccessRows_andIgnoreNullAmounts() {
    assertThat(
            OrderRefundStatuses.of(
                List.of(refund("PROCESSING", null), refund("SUCCESS", null)),
                new BigDecimal("5.00")))
        .isEqualTo(OrderRefundStatuses.PROCESSING);
    assertThat(OrderRefundStatuses.of(List.of(refund("SUCCESS", null)), new BigDecimal("5.00")))
        .isEqualTo(OrderRefundStatuses.NONE);
  }

  @Test
  void shouldNeverReportFullSuccess_withoutPaidAmount() {
    assertThat(OrderRefundStatuses.of(List.of(refund("SUCCESS", "5.00")), null))
        .isEqualTo(OrderRefundStatuses.PARTIAL_SUCCESS);
  }
}
