package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.api.CreatorFundFlowApi;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper.Posting;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper.Snapshot;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatorFundFlowService implements CreatorFundFlowApi {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
  private final CreatorFundFlowMapper flows;
  private final CommissionFinanceQueryMapper finance;
  private final CommissionWalletMapper wallet;
  private final WithdrawalMapper withdrawals;
  private final WithdrawalFundingSource funding;
  private final KolApi creators;
  private final ShopAccessApi shops;

  public CreatorFundFlowService(
      CreatorFundFlowMapper flows,
      CommissionFinanceQueryMapper finance,
      CommissionWalletMapper wallet,
      WithdrawalMapper withdrawals,
      WithdrawalFundingSource funding,
      KolApi creators,
      ShopAccessApi shops) {
    this.flows = flows;
    this.finance = finance;
    this.wallet = wallet;
    this.withdrawals = withdrawals;
    this.funding = funding;
    this.creators = creators;
    this.shops = shops;
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<FundFlow> list(AccountDTO user, Query query) {
    if (user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return TenantScope.callCompany(user.tenantId(), () -> listInCompany(user, query));
  }

  private PageResult<FundFlow> listInCompany(AccountDTO user, Query query) {
    var scope = displayScope(user, query);
    var rows = new ArrayList<>(balances(user.tenantId(), scope));
    appendPostings(
        rows,
        flows.withdrawals(user.tenantId(), query.creatorId(), query.from(), query.until()),
        Status.WITHDRAWN,
        scope);
    appendPostings(
        rows,
        flows.clawbacks(user.tenantId(), query.creatorId(), query.from(), query.until()),
        Status.CLAWBACK,
        scope);
    if (query.shopId() == null) {
      for (var payment :
          finance.prepayPayments(List.of(query.creatorId()), query.from(), query.until())) {
        rows.add(
            row(
                "PREPAID:" + payment.paymentId(),
                FundType.PREPAY,
                null,
                payment.amountCny(),
                Status.PREPAID,
                payment.sourceNo(),
                payment.paidAt()));
      }
    }
    int offset = (query.page() - 1) * query.size();
    return new PageResult<>(
        rows.stream()
            .sorted(
                Comparator.comparing(FundFlow::occurredAt).reversed().thenComparing(FundFlow::id))
            .skip(offset)
            .limit(query.size())
            .toList(),
        rows.size(),
        query.page(),
        query.size());
  }

  @Override
  @Transactional(readOnly = true)
  public PageResult<Application> applications(AccountDTO user, Query query) {
    if (user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          var scope = displayScope(user, query);
          var rows =
              flows.applications(
                  user.tenantId(),
                  query.creatorId(),
                  query.shopId(),
                  scope.owner(),
                  scope.visible(),
                  query.from(),
                  query.until());
          long offset = (long) (query.page() - 1) * query.size();
          return new PageResult<>(
              rows.stream().skip(offset).limit(query.size()).toList(),
              rows.size(),
              query.page(),
              query.size());
        });
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Totals totals(AccountDTO user, Query query) {
    if (user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          var scope = displayScope(user, query);
          var balances = balances(user.tenantId(), scope);
          var frozen =
              balances.stream()
                  .filter(row -> row.status() == Status.FROZEN)
                  .map(FundFlow::amount)
                  .reduce(ZERO, BigDecimal::add);
          var available =
              balances.stream()
                  .filter(row -> row.status() == Status.AVAILABLE)
                  .map(FundFlow::amount)
                  .reduce(ZERO, BigDecimal::add);
          var withdrawn =
              flows
                  .withdrawals(user.tenantId(), query.creatorId(), query.from(), query.until())
                  .stream()
                  .filter(row -> scope.includes(row.shopId(), row.ownerId()))
                  .map(Posting::amount)
                  .reduce(ZERO, BigDecimal::add);
          var prepaid =
              query.shopId() == null
                  ? finance
                      .prepayPayments(List.of(query.creatorId()), query.from(), query.until())
                      .stream()
                      .map(payment -> payment.amountCny())
                      .reduce(ZERO, BigDecimal::add)
                  : null;
          return new Totals(frozen, available, withdrawn, prepaid);
        });
  }

  private DisplayScope displayScope(AccountDTO user, Query query) {
    var creator =
        creators.listByIds(List.of(query.creatorId())).stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND));
    Long owner = user.ownScope() ? user.id() : null;
    if (owner != null && !Objects.equals(creator.bdUserId(), owner)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var visible = shops.listShops().stream().map(ShopAccessDTO::id).collect(Collectors.toSet());
    if (query.shopId() != null && !visible.contains(query.shopId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return new DisplayScope(query, owner, visible);
  }

  private List<FundFlow> balances(long tenantId, DisplayScope scope) {
    var query = scope.query();
    var snapshots = flows.snapshots(tenantId, query.creatorId());
    if (snapshots.isEmpty()) {
      return List.of();
    }
    var occupied = new HashMap<Long, BigDecimal>();
    for (var occupation : withdrawals.listOccupationsByKol(query.creatorId())) {
      occupied.merge(occupation.orderItemId(), occupation.occupiedCny(), BigDecimal::add);
    }
    var active =
        withdrawals.sumActiveOccupiedByKol(List.of(query.creatorId())).stream()
            .map(WithdrawalMapper.KolAmountRow::amountCny)
            .reduce(ZERO, BigDecimal::add);
    BigDecimal budget =
        nonnegative(wallet.findBalance(query.creatorId())).subtract(active).max(ZERO);
    var available = new HashMap<Long, BigDecimal>();
    // Apply the shared withdrawal FIFO to the whole creator wallet before restricting the view.
    for (var fundingRow : funding.listReleasedRows(query.creatorId())) {
      BigDecimal amount = budget.min(fundingRow.releasedCny());
      available.put(fundingRow.itemStateId(), amount);
      budget = budget.subtract(amount);
    }
    Map<BalanceKey, FundFlow> rows = new HashMap<>();
    for (var item : snapshots) {
      var isAllocatable = available.containsKey(item.stateId());
      var blocked =
          isAllocatable
              ? ZERO
              : item.released()
                  .subtract(occupied.getOrDefault(item.orderItemId(), ZERO))
                  .max(ZERO)
                  .min(budget);
      budget = budget.subtract(blocked);
      if (scope.includes(item.shopId(), item.ownerId()) && inPeriod(item.payTime(), query)) {
        addBalance(
            rows,
            item,
            Status.FROZEN,
            item.accrued().subtract(item.released()).max(ZERO).add(blocked));
        addBalance(rows, item, Status.AVAILABLE, available.getOrDefault(item.stateId(), ZERO));
      }
    }
    return List.copyOf(rows.values());
  }

  private static void addBalance(
      Map<BalanceKey, FundFlow> rows, Snapshot item, Status status, BigDecimal amount) {
    if (amount.signum() > 0) {
      var key = new BalanceKey(item.orderId(), status);
      var existing = rows.get(key);
      var total = existing == null ? amount : existing.amount().add(amount);
      rows.put(
          key,
          row(
              status + ":" + item.orderId(),
              FundType.COMMISSION,
              item.orderNo(),
              total,
              status,
              null,
              item.payTime()));
    }
  }

  private static void appendPostings(
      List<FundFlow> rows, List<Posting> postings, Status status, DisplayScope scope) {
    for (var posting : postings) {
      if (scope.includes(posting.shopId(), posting.ownerId())) {
        rows.add(
            row(
                status + ":" + posting.id(),
                FundType.COMMISSION,
                posting.orderNo(),
                posting.amount(),
                status,
                posting.settlementNo(),
                posting.occurredAt()));
      }
    }
  }

  private static FundFlow row(
      String id,
      FundType type,
      String orderNo,
      BigDecimal amount,
      Status status,
      String settlementNo,
      Instant occurredAt) {
    return new FundFlow(
        id,
        type,
        amount.signum() < 0 ? Direction.DECREASE : Direction.INCREASE,
        orderNo,
        amount.setScale(2),
        status,
        settlementNo,
        occurredAt);
  }

  private static boolean inPeriod(Instant time, Query query) {
    return time != null && !time.isBefore(query.from()) && time.isBefore(query.until());
  }

  private static BigDecimal nonnegative(BigDecimal value) {
    return value == null ? ZERO : value.max(ZERO);
  }

  private record BalanceKey(long orderId, Status status) {}

  private record DisplayScope(Query query, Long owner, Set<Long> visible) {
    boolean includes(Long shopId, Long orderOwner) {
      return (owner == null || owner.equals(orderOwner))
          && (query.shopId() == null || query.shopId().equals(shopId))
          && (shopId == null || visible.contains(shopId));
    }
  }
}
