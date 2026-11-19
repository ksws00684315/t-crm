package com.tcrm.admin.stats.service;

import com.tcrm.admin.commission.api.WithdrawalWorkbenchApi;
import com.tcrm.admin.kol.api.KolWorkbenchApi;
import com.tcrm.admin.product.api.ProductWorkbenchApi;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Board;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchService {
  private static final Logger LOG = LoggerFactory.getLogger(WorkbenchService.class);

  private final CurrentUserContext current;
  private final StatsOverviewService stats;
  private final ShopPerformanceService shopRanking;
  private final AnalyticsRankingService rankings;
  private final ProductWorkbenchApi product;
  private final KolWorkbenchApi kol;
  private final WithdrawalWorkbenchApi withdrawal;

  public WorkbenchService(
      CurrentUserContext current,
      StatsOverviewService stats,
      ShopPerformanceService shopRanking,
      AnalyticsRankingService rankings,
      ProductWorkbenchApi product,
      KolWorkbenchApi kol,
      WithdrawalWorkbenchApi withdrawal) {
    this.current = current;
    this.stats = stats;
    this.shopRanking = shopRanking;
    this.rankings = rankings;
    this.product = product;
    this.kol = kol;
    this.withdrawal = withdrawal;
  }

  public Overview overview() {
    var user = current.user();
    if (user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var permissions = current.effectivePermissions(user);
    Instant now = Instant.now();
    LocalDate day = now.atZone(StatsPeriod.ZONE).toLocalDate();
    var filter = new FilterDTO(day, day, null, null, null, null).withAffiliateOnly(true);
    List<String> unavailable = new ArrayList<>();
    boolean canShop = permissions.contains("dashboard:shop");
    boolean canCreator = permissions.contains("dashboard:kol");
    boolean canProduct = permissions.contains("product:view");
    boolean canKol = permissions.contains("kol:view");
    boolean canWithdrawal =
        permissions.contains("withdrawal:review") || permissions.contains("withdrawal:pay");

    var sales =
        read(
            canShop || canCreator,
            "sales",
            unavailable,
            () -> {
              var metrics = stats.overview(filter).summary();
              return new Sales(metrics.salesAmount(), metrics.paidOrderCount());
            });
    var topShops =
        read(
            canShop,
            "topShops",
            unavailable,
            () ->
                shopRanking.query(filter, 1, 20, "salesAmount", "desc").ranking().items().stream()
                    .filter(row -> row.paidOrderCount() > 0)
                    .limit(5)
                    .map(
                        row ->
                            new Rank(
                                row.shopName(),
                                row.shopCode(),
                                row.paidOrderCount(),
                                row.salesAmount()))
                    .toList());
    var topCreators =
        read(
            canCreator,
            "topCreators",
            unavailable,
            () ->
                rankings
                    .query(Board.CREATOR, filter, null, 1, 20, "salesAmount", "desc")
                    .ranking()
                    .items()
                    .stream()
                    .filter(row -> row.metrics().paidOrderCount() > 0)
                    .limit(5)
                    .map(
                        row ->
                            new Rank(
                                row.name(),
                                row.code(),
                                row.metrics().units(),
                                row.metrics().salesAmount()))
                    .toList());
    var shops = read(canProduct, "shops", unavailable, product::shops);
    var activeLinks = read(canProduct, "activeLinks", unavailable, product::activeLinks);
    var jobs = read(canProduct, "jobs", unavailable, product::failedJobs);
    var activeTokens =
        read(
            permissions.contains("kol:copy-token"), "activeTokens", unavailable, kol::activeTokens);
    var creators = read(canKol, "creators", unavailable, kol::counts);
    var pending = read(canKol, "pending", unavailable, kol::pending);
    var payouts = read(canWithdrawal, "payouts", unavailable, () -> withdrawal.pending(user));

    boolean canCommission = (canShop || canCreator) && permissions.contains("commission:view");
    var estimatedCommission =
        read(
            canCommission,
            "estimatedCommission",
            unavailable,
            () -> stats.unsettledCommission(filter));
    return new Overview(
        day,
        now,
        sales,
        topShops,
        topCreators,
        shops,
        activeLinks,
        activeTokens,
        jobs,
        creators,
        pending,
        payouts,
        estimatedCommission,
        unavailable);
  }

  private static <T> T read(
      boolean allowed, String field, List<String> unavailable, Supplier<T> fetch) {
    if (!allowed) {
      return null;
    }
    try {
      T value = fetch.get();
      if (value == null) {
        unavailable.add(field);
      }
      return value;
    } catch (DataAccessException error) {
      LOG.warn("Workbench query failed for {} ({})", field, error.getClass().getSimpleName());
      unavailable.add(field);
      return null;
    }
  }

  public record Overview(
      LocalDate businessDate,
      Instant asOf,
      Sales sales,
      List<Rank> topShops,
      List<Rank> topCreators,
      ProductWorkbenchApi.ShopSummary shops,
      Long activeLinks,
      Long activeTokens,
      ProductWorkbenchApi.FailedJobs jobs,
      KolWorkbenchApi.Counts creators,
      KolWorkbenchApi.Pending pending,
      WithdrawalWorkbenchApi.Pending payouts,
      BigDecimal estimatedCommission,
      List<String> unavailable) {}

  public record Sales(BigDecimal amount, long orders) {}

  public record Rank(String name, String code, long quantity, BigDecimal amount) {}
}
