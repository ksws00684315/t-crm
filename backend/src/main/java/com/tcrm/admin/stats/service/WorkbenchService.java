package com.tcrm.admin.stats.service;

import com.tcrm.admin.commission.api.WithdrawalWorkbenchApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.api.KolWorkbenchApi;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.CommissionWindowDTO;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.SalesWindowDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.product.api.ProductWorkbenchApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchService {
  private static final Logger LOG = LoggerFactory.getLogger(WorkbenchService.class);

  private final CurrentUserContext current;
  private final StatsOverviewService stats;
  private final OrderStatsApi orders;
  private final ShopAccessApi shopAccess;
  private final KolApi kolDirectory;
  private final ProductWorkbenchApi product;
  private final KolWorkbenchApi kol;
  private final WithdrawalWorkbenchApi withdrawal;

  public WorkbenchService(
      CurrentUserContext current,
      StatsOverviewService stats,
      OrderStatsApi orders,
      ShopAccessApi shopAccess,
      KolApi kolDirectory,
      ProductWorkbenchApi product,
      KolWorkbenchApi kol,
      WithdrawalWorkbenchApi withdrawal) {
    this.current = current;
    this.stats = stats;
    this.orders = orders;
    this.shopAccess = shopAccess;
    this.kolDirectory = kolDirectory;
    this.product = product;
    this.kol = kol;
    this.withdrawal = withdrawal;
  }

  public Overview overview() {
    var user = tenantUser();
    var permissions = current.effectivePermissions(user);
    Instant now = Instant.now();
    LocalDate day = now.atZone(StatsPeriod.ZONE).toLocalDate();
    var filter = new FilterDTO(day, day, null, null, null, null).withAffiliateOnly(true);
    List<String> unavailable = new ArrayList<>();
    boolean canShop = permissions.contains(Ranking.SHOP.permission);
    boolean canCreator = permissions.contains(Ranking.CREATOR.permission);
    boolean canProduct = permissions.contains("product:view");
    boolean canKol = permissions.contains("kol:view");
    boolean canWithdrawal =
        permissions.contains("withdrawal:review") || permissions.contains("withdrawal:pay");

    var sales =
        read(canShop || canCreator, "sales", unavailable, () -> stats.workbenchSales(filter, now));
    var topShops =
        read(canShop, "topShops", unavailable, () -> ranks(user, Ranking.SHOP, 1, 5, null, now).items());
    var topCreators =
        read(
            canCreator,
            "topCreators",
            unavailable,
            () -> ranks(user, Ranking.CREATOR, 1, 5, null, now).items());
    var shops = read(canProduct, "shops", unavailable, product::shops);
    var jobs = read(canProduct, "jobs", unavailable, product::failedJobs);
    var processingJobs = read(canProduct, "processingJobs", unavailable, product::processingJobs);
    var creators =
        read(
            canKol,
            "creators",
            unavailable,
            () -> kol.counts(day.atStartOfDay(StatsPeriod.ZONE).toInstant(), now));
    var promoting = read(canKol, "promoting", unavailable, kol::promoting);
    var pending = read(canKol, "pending", unavailable, kol::pending);
    boolean canPrepay =
        permissions.contains("prepay:review")
            || permissions.contains("prepay:finance-review")
            || permissions.contains("prepay:pay");
    var prepays =
        read(canPrepay, "prepays", unavailable, () -> withdrawal.pendingPrepays(user));
    var payouts = read(canWithdrawal, "payouts", unavailable, () -> withdrawal.pending(user));

    boolean canCommission = (canShop || canCreator) && permissions.contains("commission:view");
    var commission =
        read(
            canCommission, "commission", unavailable, () -> stats.workbenchCommission(filter, now));
    return new Overview(
        day,
        now,
        sales,
        topShops,
        topCreators,
        shops,
        jobs,
        processingJobs,
        creators,
        promoting,
        pending,
        payouts,
        prepays,
        commission,
        unavailable);
  }

  public PageResult<Rank> ranks(Ranking board, RankPeriod period, int page, int size) {
    var user = tenantUser();
    if (!current.effectivePermissions(user).contains(board.permission)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (page < 1 || page > PageResult.MAX_PAGE || (size != 5 && size != 10)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var now = Instant.now();
    var from =
        period.days == 0
            ? null
            : now.atZone(StatsPeriod.ZONE)
                .toLocalDate()
                .minusDays(period.days - 1)
                .atStartOfDay(StatsPeriod.ZONE)
                .toInstant();
    return ranks(user, board, page, size, from, now);
  }

  private AccountDTO tenantUser() {
    var user = current.user();
    if (user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user;
  }

  private PageResult<Rank> ranks(
      AccountDTO user, Ranking board, int page, int size, Instant from, Instant now) {
    var scope =
        new ScopeDTO(
            user.tenantId(),
            user.ownScope() ? user.id() : null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true);
    var shops =
        board == Ranking.SHOP
            ? shopAccess.listShops().stream()
                .collect(Collectors.toMap(ShopAccessDTO::id, Function.identity()))
            : java.util.Map.<Long, ShopAccessDTO>of();
    if (board == Ranking.SHOP) {
      scope = scope.withVisibleShops(List.copyOf(shops.keySet()));
    }
    var facts = orders.workbenchRanks(scope, board.dimension, from, now);
    var selected = facts.stream().skip((long) (page - 1) * size).limit(size).toList();
    var creators =
        board == Ranking.CREATOR
            ? kolDirectory
                .listByIdsIncludingDeleted(
                    selected.stream().map(OrderStatsApi.WorkbenchRankDTO::entityId).toList())
                .stream()
                .collect(Collectors.toMap(KolRefDTO::id, Function.identity()))
            : java.util.Map.<Long, KolRefDTO>of();
    var rows =
        selected.stream()
            .map(
                row -> {
                  var shop = shops.get(row.entityId());
                  var creator = creators.get(row.entityId());
                  if (shop != null) {
                    return new Rank(
                        row.entityId(), shop.name(), shop.shopCode(), row.quantity(), row.amount());
                  }
                  return new Rank(
                      row.entityId(),
                      creator == null ? null : creator.name(),
                      creator == null ? null : creator.kolCode(),
                      row.quantity(),
                      row.amount());
                })
            .toList();
    return new PageResult<>(rows, facts.size(), page, size);
  }

  public enum RankPeriod {
    TODAY(1),
    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    ALL_TIME(0);

    private final int days;

    RankPeriod(int days) {
      this.days = days;
    }
  }

  public enum Ranking {
    SHOP("dashboard:shop", RankDimension.SHOP),
    CREATOR("dashboard:kol", RankDimension.KOL);

    private final String permission;
    private final RankDimension dimension;

    Ranking(String permission, RankDimension dimension) {
      this.permission = permission;
      this.dimension = dimension;
    }
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
      SalesWindowDTO sales,
      List<Rank> topShops,
      List<Rank> topCreators,
      ProductWorkbenchApi.ShopSummary shops,
      ProductWorkbenchApi.FailedJobs jobs,
      Long processingJobs,
      KolWorkbenchApi.Counts creators,
      Long promoting,
      KolWorkbenchApi.Pending pending,
      WithdrawalWorkbenchApi.Pending payouts,
      Long prepays,
      CommissionWindowDTO commission,
      List<String> unavailable) {}

  public record Rank(long id, String name, String code, long quantity, BigDecimal amount) {}
}
