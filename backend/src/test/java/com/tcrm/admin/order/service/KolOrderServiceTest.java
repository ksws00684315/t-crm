package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolOrderServiceTest {
  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final KolOrderService service = new KolOrderService(orders, items, current);

  @BeforeEach
  void setUp() {
    when(current.kol()).thenReturn(kol());
    when(items.selectList(any())).thenReturn(List.of(item(21L, 700L, "25.00", "5.00")));
  }

  @Test
  void shouldReturnMaskedAttributedOrderAndOnlyOwnItemAmounts() {
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    Page<OrderDO> page = page(order);
    when(orders.selectPage(any(), any())).thenReturn(page);

    var result = service.page(new KolOrderFilterDTO(null, null, null, 1, 20));

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items())
        .containsExactly(
            new KolOrderDTO(
                "1234****9012",
                order.getPayTime(),
                "PAID",
                new BigDecimal("25.00"),
                new BigDecimal("5.00"),
                List.of(
                    new KolOrderDTO.Line(
                        "Widget", "Blue", 2, new BigDecimal("25.00"), new BigDecimal("5.00")))));
    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("kol_id")
        .contains("attribution_status")
        .contains("pay_time");
  }

  @Test
  void shouldKeepOnlyLastTwoCharacters_whenOrderNumberIsEightCharactersOrShorter() {
    assertThat(KolOrderService.maskOrderNo("12345678")).isEqualTo("78");
    assertThat(KolOrderService.maskOrderNo("1234")).isEqualTo("34");
  }

  @Test
  void shouldRejectSizeAboveFifty() {
    assertThatThrownBy(() -> service.page(new KolOrderFilterDTO(null, null, null, 1, 51)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldRejectInvalidDateRange() {
    assertThatThrownBy(
            () ->
                service.page(
                    new KolOrderFilterDTO(
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null,
                        1,
                        20)))
        .isInstanceOf(BusinessException.class);
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        700L,
        "KOL0700",
        "Creator",
        "creator@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        77L,
        "Company",
        true);
  }

  private static OrderDO order(long id, String orderNo, String attribution, String status) {
    OrderDO value = new OrderDO();
    value.setId(id);
    value.setKolId(700L);
    value.setWeidianOrderId(orderNo);
    value.setAttributionStatus(attribution);
    value.setOrderStatus(status);
    value.setPayTime(Instant.parse("2026-10-15T12:00:00Z"));
    return value;
  }

  private static OrderItemDO item(long id, long kolId, String pay, String refund) {
    OrderItemDO value = new OrderItemDO();
    value.setId(id);
    value.setOrderId(21L);
    value.setKolId(kolId);
    value.setItemName("Widget");
    value.setSkuName("Blue");
    value.setQuantity(2);
    value.setPayAmount(new BigDecimal(pay));
    value.setRefundAmount(new BigDecimal(refund));
    return value;
  }

  private static Page<OrderDO> page(OrderDO... rows) {
    Page<OrderDO> page = new Page<>(1, 20);
    page.setRecords(List.of(rows));
    page.setTotal(rows.length);
    return page;
  }
}
