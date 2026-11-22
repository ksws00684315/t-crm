package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.api.OrderChainCommissionApi.FlowDTO;
import com.tcrm.admin.order.api.OrderChainCommissionApi.LineCommissionDTO;
import com.tcrm.admin.order.api.OrderChainCommissionApi.OrderCommissionChainDTO;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderChainAccrualTest {
  private static final Instant PAID = Instant.parse("2026-11-21T02:00:00Z");

  @Test
  void aCompletedPartialRefundOnlyReducesItsLineAndKeepsReleasedCommissionAvailable() {
    var order = new OrderDO();
    order.setWeidianOrderId("991121000001");
    order.setPayTime(PAID);
    order.setPayAmount(new BigDecimal("150"));
    var refundedItem = new OrderItemDO();
    refundedItem.setId(11L);
    refundedItem.setPayAmount(new BigDecimal("100"));
    var unaffectedItem = new OrderItemDO();
    unaffectedItem.setId(12L);
    unaffectedItem.setPayAmount(new BigDecimal("50"));

    var refund = new RefundDO();
    refund.setId(21L);
    refund.setOrderItemId(11L);
    refund.setAmount(new BigDecimal("20"));
    refund.setRefundStatus("SUCCESS");
    refund.setFinishTime(PAID.plusSeconds(120));
    var commission =
        new OrderCommissionChainDTO(
            new BigDecimal("31.00"),
            true,
            List.of(confirmedLine(11L, "0.20", "16", "80"), confirmedLine(12L, "0.30", "15", "50")),
            List.of(
                releasedFlow(11L, "CL1", "INCREASE", "20", PAID),
                releasedFlow(12L, "CL2", "INCREASE", "15", PAID),
                releasedFlow(11L, "CL3", "DECREASE", "4", refund.getFinishTime())),
            new BigDecimal("130"),
            false);

    var confirmed =
        OrderChainAccrual.of(
            order, List.of(refundedItem, unaffectedItem), List.of(refund), commission, "XD");
    assertThat(confirmed.amount()).isEqualByComparingTo("31.00");
    assertThat(confirmed.commissionStatus(commission)).isEqualTo("WITHDRAWABLE");
    var deduction = confirmed.flows().getLast();
    assertThat(deduction.orderItemId()).isEqualTo(11L);
    assertThat(deduction.amount()).isEqualByComparingTo("4.00");

    refund.setOrderItemId(null);
    var unmatched =
        OrderChainAccrual.of(
            order, List.of(refundedItem, unaffectedItem), List.of(refund), commission, "XD");
    assertThat(unmatched.amount()).isNull();
    assertThat(unmatched.flows().getLast().amount()).isNull();
  }

  private static LineCommissionDTO confirmedLine(
      long itemId, String rate, String net, String basis) {
    return new LineCommissionDTO(
        itemId,
        new BigDecimal(rate),
        false,
        new BigDecimal(net),
        "CONFIRMED",
        BigDecimal.ZERO,
        List.of(),
        new BigDecimal(basis));
  }

  private static FlowDTO releasedFlow(
      long itemId, String id, String direction, String amount, Instant at) {
    return new FlowDTO(
        itemId, id, "KOL_COMMISSION", direction, new BigDecimal(amount), at, null, null);
  }

  @Test
  void shouldShowPaymentAccrualAndAdjustTheSameRefundEventFromPendingToSuccess() {
    var order = new OrderDO();
    order.setWeidianOrderId("991121000001");
    order.setPayTime(PAID);
    order.setPayAmount(new BigDecimal("150.00"));
    var item = new OrderItemDO();
    item.setId(11L);
    item.setPayAmount(new BigDecimal("150.00"));

    var frozen =
        OrderChainAccrual.of(order, List.of(item), List.of(), commission("30", false), "XD");
    assertThat(frozen.status()).isEqualTo("FROZEN");
    assertThat(frozen.amount()).isEqualByComparingTo("0");
    assertThat(frozen.flows()).hasSize(2);
    assertThat(frozen.flows().getFirst().type()).isEqualTo("SHOP_RECEIVABLE");
    assertThat(frozen.flows().getFirst().amount()).isEqualByComparingTo("150");
    assertThat(frozen.flows().getLast().type()).isEqualTo("COMMISSION_ACCRUAL");
    assertThat(frozen.flows().getLast().amount()).isEqualByComparingTo("30");
    assertThat(frozen.flows().getLast().transferredAt()).isNull();

    var refund = new RefundDO();
    refund.setId(21L);
    refund.setOrderItemId(11L);
    refund.setRefundTime(PAID.plusSeconds(60));
    refund.setAmount(new BigDecimal("20"));
    refund.setRefundStatus("PROCESSING");
    var pending =
        OrderChainAccrual.of(
            order, List.of(item), List.of(refund, refund), commission("0", true), "XD");
    assertThat(pending.status()).isEqualTo("REFUNDING");
    assertThat(pending.amount()).isEqualByComparingTo("26");
    assertThat(pending.flows()).hasSize(4);
    assertThat(pending.flows().get(2).type()).isEqualTo("SHOP_RECEIVABLE");
    assertThat(pending.flows().get(2).amount()).isEqualByComparingTo("20");
    assertThat(pending.flows().getLast().amount()).isEqualByComparingTo("4");
    assertThat(pending.flows().getLast().type()).isEqualTo("COMMISSION_REFUND_PENDING");
    assertThat(pending.flows().getLast().commissionStatus()).isEqualTo("FROZEN");
    assertThat(pending.commissionStatus(commission("0", true))).isEqualTo("FROZEN");

    refund.setRefundStatus("SUCCESS");
    refund.setFinishTime(PAID.plusSeconds(120));
    var confirmed =
        OrderChainAccrual.of(order, List.of(item), List.of(refund), commission("26", false), "XD");
    assertThat(confirmed.status()).isEqualTo("REFUNDED");
    assertThat(confirmed.amount()).isEqualByComparingTo("26");
    assertThat(confirmed.flows().getLast().flowNo()).isEqualTo(pending.flows().getLast().flowNo());
    assertThat(confirmed.flows().getLast().type()).isEqualTo("COMMISSION_REFUND");
    assertThat(confirmed.flows().getLast().commissionStatus()).isEqualTo("INVALID");
    assertThat(confirmed.commissionStatus(commission("26", false))).isEqualTo("FROZEN");
    assertThat(confirmed.flows().getLast().businessAt()).isEqualTo(refund.getFinishTime());

    refund.setAmount(new BigDecimal("150"));
    var full =
        OrderChainAccrual.of(order, List.of(item), List.of(refund), commission("0", false), "XD");
    assertThat(full.amount()).isEqualByComparingTo("0");
    assertThat(full.commissionStatus(commission("0", false))).isEqualTo("INVALID");
    assertThat(full.flows().getLast().amount()).isEqualByComparingTo("30");
  }

  private OrderCommissionChainDTO commission(String net, boolean afterSales) {
    var line =
        new LineCommissionDTO(
            11L,
            new BigDecimal("0.20"),
            false,
            new BigDecimal(net),
            "UNSETTLED",
            BigDecimal.ZERO,
            List.of(),
            new BigDecimal("150"));
    return new OrderCommissionChainDTO(
        new BigDecimal(net), true, List.of(line), List.of(), null, afterSales);
  }
}
