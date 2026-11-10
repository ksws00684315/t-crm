package com.tcrm.admin.stats.service;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Shared management sales and refund overview. */
@Service
public class StatsOverviewService {
  private static final String WEIDIAN = "WEIDIAN";
  private static final List<UnavailableDTO> UNAVAILABLE =
      List.of(
          new UnavailableDTO("settlementNetReceivable", "WEIDIAN_SETTLEMENT_PENDING"),
          new UnavailableDTO("netCommission", "WEIDIAN_SETTLEMENT_PENDING"),
          new UnavailableDTO("shopTraffic", "WEIDIAN_TRAFFIC_PENDING"));

  private final OrderStatsApi orders;
  private final CurrentUserContext current;

  public StatsOverviewService(OrderStatsApi orders, CurrentUserContext current) {
    this.orders = orders;
    this.current = current;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OverviewDTO overview(FilterDTO filter) {
    return overview(resolve(filter));
  }

  ResolvedFilter resolve(FilterDTO filter) {
    var account = current.user();
    if (account.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    String channel = filter.channel();
    if (channel != null && !channel.isBlank() && !WEIDIAN.equals(channel)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var period =
        StatsPeriod.resolve(filter.startDate(), filter.endDate(), LocalDate.now(StatsPeriod.ZONE));
    var scope =
        new ScopeDTO(
            account.tenantId(),
            account.ownScope() ? account.id() : null,
            filter.shopId(),
            filter.kolId(),
            filter.inviterId());
    return new ResolvedFilter(
        scope,
        period,
        new AppliedFiltersDTO(filter.shopId(), filter.kolId(), filter.inviterId(), WEIDIAN),
        account.ownScope() ? DataScope.OWN : DataScope.ALL);
  }

  OverviewDTO overview(ResolvedFilter resolved) {
    var period = resolved.period();
    var previousPeriod = period.previous();
    var scope = resolved.scope();
    var totals = orders.totals(scope, period.from(), period.until());
    var previousTotals = orders.totals(scope, previousPeriod.from(), previousPeriod.until());
    var metrics = metrics(totals);
    var previous = metrics(previousTotals);
    var meta =
        new MetaDTO(
            period,
            previousPeriod,
            StatsPeriod.ZONE.getId(),
            resolved.filters(),
            resolved.dataScope(),
            totals.updatedAt());
    return new OverviewDTO(meta, metrics, previous, changes(metrics, previous), UNAVAILABLE);
  }

  private static MetricsDTO metrics(TotalsDTO totals) {
    var paidOrders = BigDecimal.valueOf(totals.paidOrders());
    return new MetricsDTO(
        totals.paidOrders(),
        totals.units(),
        totals.sales(),
        totals.cohortRefunds(),
        totals.completedRefunds(),
        totals.sales().subtract(totals.cohortRefunds()),
        totals.refundedOrders(),
        divide(totals.sales(), paidOrders, 2),
        divide(totals.completedRefunds(), totals.sales(), 6),
        divide(BigDecimal.valueOf(totals.refundedOrders()), paidOrders, 6));
  }

  private static ChangesDTO changes(MetricsDTO value, MetricsDTO prior) {
    return new ChangesDTO(
        change(
            BigDecimal.valueOf(value.paidOrderCount()), BigDecimal.valueOf(prior.paidOrderCount())),
        change(BigDecimal.valueOf(value.units()), BigDecimal.valueOf(prior.units())),
        change(value.salesAmount(), prior.salesAmount()),
        change(value.cohortRefundAmount(), prior.cohortRefundAmount()),
        change(value.completedRefundAmount(), prior.completedRefundAmount()),
        change(value.netSalesAmount(), prior.netSalesAmount()),
        change(
            BigDecimal.valueOf(value.refundedOrderCount()),
            BigDecimal.valueOf(prior.refundedOrderCount())),
        change(value.averageOrderAmount(), prior.averageOrderAmount()),
        change(value.amountRefundRate(), prior.amountRefundRate()),
        change(value.periodRefundedOrderRate(), prior.periodRefundedOrderRate()));
  }

  private static BigDecimal change(BigDecimal value, BigDecimal prior) {
    return value == null || prior == null ? null : divide(value.subtract(prior), prior.abs(), 6);
  }

  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator, int scale) {
    return denominator.signum() == 0
        ? null
        : numerator.divide(denominator, scale, RoundingMode.HALF_UP);
  }

  public record FilterDTO(
      LocalDate startDate,
      LocalDate endDate,
      Long shopId,
      Long kolId,
      Long inviterId,
      String channel) {}

  public record AppliedFiltersDTO(Long shopId, Long kolId, Long inviterId, String channel) {}

  record ResolvedFilter(
      ScopeDTO scope, StatsPeriod period, AppliedFiltersDTO filters, DataScope dataScope) {}

  public record MetaDTO(
      StatsPeriod period,
      StatsPeriod previousPeriod,
      String timezone,
      AppliedFiltersDTO filters,
      DataScope dataScope,
      Instant updatedAt) {}

  public record OverviewDTO(
      MetaDTO meta,
      MetricsDTO summary,
      MetricsDTO previous,
      ChangesDTO change,
      List<UnavailableDTO> unavailable) {}

  public record MetricsDTO(
      long paidOrderCount,
      long units,
      BigDecimal salesAmount,
      BigDecimal cohortRefundAmount,
      BigDecimal completedRefundAmount,
      BigDecimal netSalesAmount,
      long refundedOrderCount,
      BigDecimal averageOrderAmount,
      BigDecimal amountRefundRate,
      BigDecimal periodRefundedOrderRate) {}

  public record ChangesDTO(
      BigDecimal paidOrderCount,
      BigDecimal units,
      BigDecimal salesAmount,
      BigDecimal cohortRefundAmount,
      BigDecimal completedRefundAmount,
      BigDecimal netSalesAmount,
      BigDecimal refundedOrderCount,
      BigDecimal averageOrderAmount,
      BigDecimal amountRefundRate,
      BigDecimal periodRefundedOrderRate) {}

  public record UnavailableDTO(String field, String reason) {}
}
