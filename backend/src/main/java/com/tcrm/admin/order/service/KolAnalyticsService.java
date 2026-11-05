package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
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
 * session. Sales are the order pay amounts (freight included, refunds not deducted; task 112),
 * matching the /kol-api/orders order amount; commission figures come from the commission port.
 */
@Service
public class KolAnalyticsService {
  private static final int MAX_SPAN_DAYS = 366;

  private final OrderMapper orders;
  private final CurrentKolContext current;
  private final CommissionOrderStateApi commissionStates;

  public KolAnalyticsService(
      OrderMapper orders, CurrentKolContext current, CommissionOrderStateApi commissionStates) {
    this.orders = orders;
    this.current = current;
    this.commissionStates = commissionStates;
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
                .select(OrderDO::getId, OrderDO::getPayTime, OrderDO::getPayAmount)
                .eq(OrderDO::getKolId, kol.id())
                .eq(OrderDO::getAttributionStatus, "ATTRIBUTED")
                .ge(OrderDO::getPayTime, from.toInstant())
                .lt(OrderDO::getPayTime, to.toInstant()));
    BigDecimal sales = BigDecimal.ZERO;
    Map<String, BigDecimal> byDay = new TreeMap<>();
    Map<String, Long> ordersByDay = new HashMap<>();
    ZoneOffset bucket = from.getOffset();
    for (OrderDO order : range) {
      BigDecimal amount =
          money(order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount());
      sales = sales.add(amount);
      String day = order.getPayTime().atZone(bucket).toLocalDate().toString();
      byDay.merge(day, amount, BigDecimal::add);
      ordersByDay.merge(day, 1L, Long::sum);
    }
    // Commission accrual of the creator's lines, grouped by the order's pay date (task 62 via the
    // commission port). The withdrawal occupation (task 63) splits the lines into withdrawing and
    // withdrawn.
    Map<Long, BigDecimal> commissionByOrder = new HashMap<>();
    BigDecimal commission = BigDecimal.ZERO;
    BigDecimal withdrawing = BigDecimal.ZERO;
    BigDecimal withdrawn = BigDecimal.ZERO;
    Map<String, BigDecimal> commissionByDay = new TreeMap<>();
    List<CommissionOrderStateApi.ItemCommissionStateDTO> commissionLines =
        commissionStates.listByKolAndOrderIds(
            kol.id(), range.stream().map(OrderDO::getId).toList());
    for (CommissionOrderStateApi.ItemCommissionStateDTO line : commissionLines) {
      BigDecimal amount =
          line.commissionAmount() == null ? BigDecimal.ZERO : line.commissionAmount();
      commission = commission.add(amount);
      commissionByOrder.merge(line.orderId(), amount, BigDecimal::add);
      if (CommissionOrderStateApi.STATUS_WITHDRAWING.equals(line.settlementStatus())) {
        withdrawing = withdrawing.add(amount);
      } else if (CommissionOrderStateApi.STATUS_WITHDRAWN.equals(line.settlementStatus())) {
        withdrawn = withdrawn.add(amount);
      }
    }
    for (OrderDO order : range) {
      BigDecimal amount = commissionByOrder.getOrDefault(order.getId(), BigDecimal.ZERO);
      if (amount.signum() == 0) {
        continue;
      }
      commissionByDay.merge(
          order.getPayTime().atZone(bucket).toLocalDate().toString(), amount, BigDecimal::add);
    }
    List<KolAnalyticsOverviewDTO.Day> daily = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> day : byDay.entrySet()) {
      daily.add(
          new KolAnalyticsOverviewDTO.Day(
              day.getKey(),
              money(day.getValue()),
              money(commissionByDay.getOrDefault(day.getKey(), BigDecimal.ZERO)),
              ordersByDay.getOrDefault(day.getKey(), 0L)));
    }
    return new KolAnalyticsOverviewDTO(
        Instant.now(),
        money(sales),
        range.size(),
        money(commission),
        money(withdrawn),
        money(withdrawing),
        money(currentFrozen(kol.id())),
        daily);
  }

  /**
   * Cumulative sales of the signed-in creator, aggregated in SQL so the 366-day cap of {@link
   * #overview} does not apply. Same basis as the overview: attributed paid orders, order pay amount
   * (freight included) before refunds.
   */
  public KolSalesTotalDTO salesTotal() {
    KolAccountDTO kol = current.kol();
    BigDecimal total = orders.sumAttributedPayAmount(kol.id());
    return new KolSalesTotalDTO(money(total == null ? BigDecimal.ZERO : total), Instant.now());
  }

  /** Current frozen commission: accrued but not yet released, over all live rows (as of now). */
  /**
   * Current frozen commission over all live rows (as of now): accrued minus released, including the
   * still-frozen part of partially released lines.
   */
  private BigDecimal currentFrozen(long kolId) {
    BigDecimal frozen = BigDecimal.ZERO;
    for (CommissionOrderStateApi.ItemCommissionStateDTO line : commissionStates.listByKol(kolId)) {
      if (line.commissionAmount() != null && line.releasedAmount() != null) {
        frozen = frozen.add(line.commissionAmount().subtract(line.releasedAmount()));
      }
    }
    return frozen;
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
