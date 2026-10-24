package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Read-only creator workbench overview (task 64). Identity and company scope are taken from the KOL
 * session. Sales are summed over the creator's own order lines, matching the /kol-api/orders
 * amounts; commission figures belong to the commission module and stay at zero until task 62.
 */
@Service
public class KolAnalyticsService {
  private static final int MAX_SPAN_DAYS = 366;

  private final OrderMapper orders;
  private final OrderItemMapper orderItems;
  private final CurrentKolContext current;

  public KolAnalyticsService(
      OrderMapper orders, OrderItemMapper orderItems, CurrentKolContext current) {
    this.orders = orders;
    this.orderItems = orderItems;
    this.current = current;
  }

  public KolAnalyticsOverviewDTO overview(String fromText, String toText) {
    OffsetDateTime from = parse(fromText);
    OffsetDateTime to = parse(toText);
    if (from == null
        || to == null
        || !from.toInstant().isBefore(to.toInstant())
        || from.until(to, ChronoUnit.DAYS) > MAX_SPAN_DAYS) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolAccountDTO kol = current.kol();
    List<OrderDO> range =
        orders.selectList(
            new LambdaQueryWrapper<OrderDO>()
                .select(OrderDO::getId, OrderDO::getPayTime)
                .eq(OrderDO::getKolId, kol.id())
                .eq(OrderDO::getAttributionStatus, "ATTRIBUTED")
                .ge(OrderDO::getPayTime, from.toInstant())
                .lt(OrderDO::getPayTime, to.toInstant()));
    BigDecimal sales = BigDecimal.ZERO;
    Map<String, BigDecimal> byDay = new TreeMap<>();
    if (!range.isEmpty()) {
      List<Long> orderIds = range.stream().map(OrderDO::getId).toList();
      Map<Long, BigDecimal> perOrder = new HashMap<>();
      for (OrderItemDO line : ownLines(orderIds, kol)) {
        BigDecimal amount = line.getPayAmount() == null ? BigDecimal.ZERO : line.getPayAmount();
        perOrder.merge(line.getOrderId(), amount, BigDecimal::add);
      }
      ZoneOffset bucket = from.getOffset();
      for (OrderDO order : range) {
        BigDecimal amount = money(perOrder.getOrDefault(order.getId(), BigDecimal.ZERO));
        sales = sales.add(amount);
        byDay.merge(
            order.getPayTime().atZone(bucket).toLocalDate().toString(), amount, BigDecimal::add);
      }
    }
    List<KolAnalyticsOverviewDTO.Day> daily = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> day : byDay.entrySet()) {
      daily.add(new KolAnalyticsOverviewDTO.Day(day.getKey(), money(day.getValue()), zero()));
    }
    return new KolAnalyticsOverviewDTO(
        Instant.now(), money(sales), range.size(), zero(), zero(), zero(), zero(), daily);
  }

  private List<OrderItemDO> ownLines(List<Long> orderIds, KolAccountDTO kol) {
    return orderItems.selectList(
        new LambdaQueryWrapper<OrderItemDO>()
            .select(OrderItemDO::getOrderId, OrderItemDO::getPayAmount)
            .in(OrderItemDO::getOrderId, orderIds)
            .eq(OrderItemDO::getKolId, kol.id()));
  }

  private static OffsetDateTime parse(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2);
  }
}
