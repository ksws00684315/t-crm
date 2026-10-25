package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.KolMoneyRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaidRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi;
import com.tcrm.admin.order.api.OrderSettlementApi.ItemRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi.OrderRefDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductAccessApi.ProductRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.AccountNameDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator income list and detail (task 62, requirement 11.02, design §6.2): per-KOL five-layer
 * money plus period payments. Occupied stays zero until task 63 stores withdrawal applications.
 */
@Service
public class CommissionIncomeService {
  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final CommissionFinanceQueryMapper finance;
  private final KolApi kols;
  private final OrderSettlementApi orderSettlement;
  private final ProductAccessApi products;
  private final AccountNameApi accountNames;

  public CommissionIncomeService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      CommissionFinanceQueryMapper finance,
      KolApi kols,
      OrderSettlementApi orderSettlement,
      ProductAccessApi products,
      AccountNameApi accountNames) {
    this.states = states;
    this.revisions = revisions;
    this.finance = finance;
    this.kols = kols;
    this.orderSettlement = orderSettlement;
    this.products = products;
    this.accountNames = accountNames;
  }

  /** List filter: {@code paymentType} narrows the period payments, keyword matches the KOL. */
  public record IncomeFilter(
      String keyword, String paymentType, Instant start, Instant end, int page, int size) {}

  /** Aggregated money of one KOL (amounts scale 2). */
  public record KolMoney(
      BigDecimal estimated,
      BigDecimal frozen,
      BigDecimal available,
      BigDecimal occupied,
      BigDecimal cumulativePaid,
      BigDecimal deducted,
      BigDecimal pendingDeduction,
      BigDecimal periodPaid,
      BigDecimal commissionPaid,
      BigDecimal prepayPaid,
      Instant lastPaidAt) {}

  /** One income row of the list. */
  public record IncomeRow(
      Long kolId,
      String kolCode,
      String name,
      String email,
      Long bdId,
      String bd,
      long orderCount,
      long quantity,
      KolMoney money,
      long unverified) {}

  /** List page plus the summary row (11.02). */
  public record IncomePage(PageResult<IncomeRow> page, Summary summary) {}

  /** Summary over the filtered set, not only the current page. */
  public record Summary(
      BigDecimal paid, long creators, long orders, long quantity, long unverified) {}

  /** Commission line of the income detail. */
  public record CommissionLine(
      long id,
      String orderNo,
      long itemId,
      String image,
      String base,
      String rate,
      Integer rateVersion,
      Long ruleVersion,
      String commission,
      String adjustment,
      String releaseBasis,
      Instant businessAt,
      String direction,
      String subject,
      Long kolId,
      String kol) {}

  /** Payment row of the income detail (prepay now; withdrawals join in task 63). */
  public record PaymentLine(
      long id,
      String sourceNo,
      String type,
      long kolId,
      String amountCny,
      Instant paidAt,
      BigDecimal deduct,
      BigDecimal deducted,
      BigDecimal pending,
      String actualAmount,
      String actualCurrency,
      Long voucher,
      Long withdrawalId) {}

  /** Income detail payload. */
  public record IncomeDetail(
      IncomeRow income, List<CommissionLine> lines, List<PaymentLine> payments) {}

  @Transactional(readOnly = true)
  public IncomePage list(AccountDTO user, IncomeFilter filter) {
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(
        tenantId,
        () -> {
          List<Long> candidates = candidateKolIds(user, filter.keyword());
          int from = (filter.page() - 1) * filter.size();
          List<Long> pageIds =
              candidates.isEmpty() || from >= candidates.size()
                  ? List.of()
                  : candidates.subList(from, Math.min(from + filter.size(), candidates.size()));
          List<IncomeRow> rows = pageIds.isEmpty() ? List.of() : buildRows(pageIds, filter);
          Summary summary = summary(candidates, filter);
          return new IncomePage(
              new PageResult<>(rows, candidates.size(), filter.page(), filter.size()), summary);
        });
  }

  @Transactional(readOnly = true)
  public IncomeDetail detail(AccountDTO user, long kolId, IncomeFilter filter) {
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(
        tenantId,
        () -> {
          KolRefDTO kol = requireVisibleKol(user, kolId);
          IncomeRow row = buildRows(List.of(kolId), filter).getFirst();
          List<CommissionLine> lines = lines(kol);
          List<PaymentLine> payments = payments(kolId, filter);
          return new IncomeDetail(row, lines, payments);
        });
  }

  private List<Long> candidateKolIds(AccountDTO user, String keyword) {
    List<Long> base;
    if (user.ownScope()) {
      base =
          kols.listByBdUserId(user.id()).stream()
              .map(KolRefDTO::id)
              .filter(Objects::nonNull)
              .toList();
    } else {
      base = finance.activeKolIds();
    }
    if (keyword != null && !keyword.isBlank()) {
      Set<Long> matches = new HashSet<>(kols.findIdsByKeyword(keyword));
      base = base.stream().filter(matches::contains).toList();
    }
    return base.stream().sorted(java.util.Comparator.reverseOrder()).toList();
  }

  private IncomeRow buildRow(KolRefDTO kol, Aggregates aggregates) {
    return new IncomeRow(
        kol.id(),
        kol.kolCode(),
        kol.name(),
        kol.email(),
        kol.bdUserId(),
        aggregates.bdNames.get(kol.bdUserId()),
        aggregates.orderCounts.getOrDefault(kol.id(), 0L),
        aggregates.quantities.getOrDefault(kol.id(), 0L),
        aggregates.money.get(kol.id()),
        aggregates.unverified.getOrDefault(kol.id(), 0L));
  }

  private List<IncomeRow> buildRows(List<Long> kolIds, IncomeFilter filter) {
    Map<Long, KolRefDTO> kolsById = new HashMap<>();
    for (KolRefDTO kol : kols.listByIds(kolIds)) {
      kolsById.put(kol.id(), kol);
    }
    Aggregates aggregates = loadAggregates(kolIds, filter);
    List<IncomeRow> rows = new ArrayList<>();
    for (Long id : kolIds) {
      KolRefDTO kol = kolsById.get(id);
      if (kol == null) {
        continue;
      }
      rows.add(buildRow(kol, aggregates));
    }
    return rows;
  }

  private record Aggregates(
      Map<Long, KolMoney> money,
      Map<Long, Long> orderCounts,
      Map<Long, Long> quantities,
      Map<Long, Long> unverified,
      Map<Long, String> bdNames) {}

  private Aggregates loadAggregates(List<Long> kolIds, IncomeFilter filter) {
    Map<Long, BigDecimal> estimated = new HashMap<>();
    Map<Long, BigDecimal> frozen = new HashMap<>();
    Map<Long, Long> unverified = new HashMap<>();
    for (var row : states.aggregateByKol(kolIds)) {
      estimated.put(row.kolId(), row.estimatedCny());
      frozen.put(row.kolId(), row.frozenCny());
      unverified.put(row.kolId(), row.unverifiedCount() == null ? 0L : row.unverifiedCount());
    }
    Map<Long, BigDecimal> balances = moneyMap(finance.walletBalances(kolIds));
    Map<Long, BigDecimal> deducted = moneyMap(finance.recoverySums(kolIds));
    Map<Long, BigDecimal> pending = moneyMap(finance.pendingDeductions(kolIds));
    Map<Long, BigDecimal> commissionPaid = moneyMap(finance.withdrawalPaidSums(kolIds));
    Map<Long, BigDecimal> prepayPaidPeriod =
        prepayMoneyMap(finance.prepayPaidSums(kolIds, filter.start(), filter.end()));
    Map<Long, BigDecimal> prepayPaidAll =
        prepayMoneyMap(finance.prepayPaidSums(kolIds, null, null));
    // lastPaidAt covers the prepay side now; withdrawal payments join in task 63.
    Map<Long, Instant> lastPaid = new HashMap<>();
    for (PrepayPaidRow row : finance.prepayPaidSums(kolIds, filter.start(), filter.end())) {
      lastPaid.put(row.kolId(), row.lastTime());
    }

    Map<Long, Long> orderCounts = new HashMap<>();
    Map<Long, Long> quantities = new HashMap<>();
    for (var row : orderSettlement.aggregateKolOrders(kolIds, filter.start(), filter.end())) {
      orderCounts.put(row.kolId(), row.orderCount());
      quantities.put(row.kolId(), row.quantity());
    }
    Set<Long> bdIds = new HashSet<>();
    for (KolRefDTO kol : kols.listByIds(kolIds)) {
      if (kol.bdUserId() != null) {
        bdIds.add(kol.bdUserId());
      }
    }
    Map<Long, String> bdNames = new HashMap<>();
    for (AccountNameDTO account : accountNames.listByIds(bdIds)) {
      bdNames.put(account.id(), account.nickname());
    }

    Map<Long, KolMoney> money = new HashMap<>();
    for (Long id : kolIds) {
      BigDecimal occupied = BigDecimal.ZERO.setScale(2); // withdrawal applications land in task 63
      BigDecimal balance = balances.getOrDefault(id, BigDecimal.ZERO.setScale(2));
      BigDecimal commission = commissionPaid.getOrDefault(id, BigDecimal.ZERO.setScale(2));
      BigDecimal prepayAll = prepayPaidAll.getOrDefault(id, BigDecimal.ZERO.setScale(2));
      BigDecimal prepayWindow = prepayPaidPeriod.getOrDefault(id, BigDecimal.ZERO.setScale(2));
      money.put(
          id,
          new KolMoney(
              estimated.getOrDefault(id, BigDecimal.ZERO.setScale(2)),
              frozen.getOrDefault(id, BigDecimal.ZERO.setScale(2)),
              balance.subtract(occupied),
              occupied,
              commission.add(prepayAll),
              deducted.getOrDefault(id, BigDecimal.ZERO.setScale(2)),
              pending.getOrDefault(id, BigDecimal.ZERO.setScale(2)),
              commission.add(prepayWindow),
              commission,
              prepayWindow,
              lastPaid.get(id)));
    }
    return new Aggregates(money, orderCounts, quantities, unverified, bdNames);
  }

  private Summary summary(List<Long> kolIds, IncomeFilter filter) {
    if (kolIds.isEmpty()) {
      return new Summary(BigDecimal.ZERO.setScale(2), 0, 0, 0, 0);
    }
    PaymentScope scope = PaymentScope.of(filter.paymentType());
    BigDecimal paid = BigDecimal.ZERO.setScale(2);
    long orders = 0;
    long quantity = 0;
    long unverified = 0;
    if (scope.includesPrepay()) {
      for (PrepayPaidRow row : finance.prepayPaidSums(kolIds, filter.start(), filter.end())) {
        paid = paid.add(row.amountCny() == null ? BigDecimal.ZERO : row.amountCny());
      }
    }
    // Commission withdrawal payments join the period sum in task 63; they are zero today.
    for (var row : orderSettlement.aggregateKolOrders(kolIds, filter.start(), filter.end())) {
      orders += row.orderCount();
      quantity += row.quantity();
    }
    for (var row : states.aggregateByKol(kolIds)) {
      unverified += row.unverifiedCount() == null ? 0 : row.unverifiedCount();
    }
    return new Summary(paid, kolIds.size(), orders, quantity, unverified);
  }

  private record PaymentScope(boolean includesPrepay, boolean includesCommission) {
    static PaymentScope of(String paymentType) {
      if ("COMMISSION".equals(paymentType)) {
        return new PaymentScope(false, true);
      }
      if ("PREPAY".equals(paymentType)) {
        return new PaymentScope(true, false);
      }
      return new PaymentScope(true, true);
    }
  }

  private List<CommissionLine> lines(KolRefDTO kol) {
    List<CommissionItemStateDO> rows = states.listByKol(kol.id());
    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> itemStateIds = rows.stream().map(CommissionItemStateDO::getId).toList();
    Map<Long, CommissionItemRevisionDO> latestRevisions = new HashMap<>();
    for (CommissionItemRevisionDO revision : revisions.listLatestByItemStateIds(itemStateIds)) {
      latestRevisions.put(revision.getItemStateId(), revision);
    }
    Set<Long> orderIds = new HashSet<>();
    for (CommissionItemStateDO row : rows) {
      orderIds.add(row.getOrderId());
    }
    Map<Long, OrderRefDTO> ordersById = new HashMap<>();
    for (OrderRefDTO order : orderSettlement.listOrdersByIds(orderIds)) {
      ordersById.put(order.id(), order);
    }
    Map<Long, ItemRefDTO> itemsById = new HashMap<>();
    Set<Long> productIds = new HashSet<>();
    for (ItemRefDTO item : orderSettlement.listItemsByOrderIds(orderIds)) {
      itemsById.put(item.id(), item);
      if (item.productId() != null) {
        productIds.add(item.productId());
      }
    }
    Map<Long, String> images = new HashMap<>();
    for (ProductRefDTO product : products.listProducts(productIds)) {
      images.put(product.id(), product.imageUrl());
    }
    List<CommissionLine> lines = new ArrayList<>(rows.size());
    for (CommissionItemStateDO row : rows) {
      OrderRefDTO order = ordersById.get(row.getOrderId());
      ItemRefDTO item = itemsById.get(row.getOrderItemId());
      CommissionItemRevisionDO revision = latestRevisions.get(row.getId());
      BigDecimal rate = revision == null ? null : revision.getRate();
      BigDecimal released =
          row.getReleasedCommissionCny() == null ? BigDecimal.ZERO : row.getReleasedCommissionCny();
      BigDecimal adjustment = released.subtract(row.getAccruedCommissionCny());
      Instant businessAt = order == null ? null : order.payTime();
      lines.add(
          new CommissionLine(
              row.getId(),
              order == null ? null : order.orderNo(),
              row.getOrderItemId(),
              item == null || item.productId() == null ? null : images.get(item.productId()),
              row.getBasisCny() == null ? null : row.getBasisCny().toPlainString(),
              rate == null ? null : rate.toPlainString(),
              row.getConfigVersion(),
              row.getRuleVersionId(),
              row.getAccruedCommissionCny().toPlainString(),
              adjustment.toPlainString(),
              row.getReleaseBasis(),
              businessAt,
              adjustment.signum() < 0 ? "DEBIT" : "CREDIT",
              row.getKolId() == null ? "COMPANY" : "KOL",
              row.getKolId(),
              kol.name()));
    }
    return lines;
  }

  private List<PaymentLine> payments(long kolId, IncomeFilter filter) {
    PaymentScope scope = PaymentScope.of(filter.paymentType());
    if (!scope.includesPrepay()) {
      // Commission withdrawal payments arrive with task 63; nothing to show for COMMISSION yet.
      return List.of();
    }
    Map<Long, BigDecimal> deducted = moneyMap(finance.recoverySums(List.of(kolId)));
    Map<Long, BigDecimal> pending = moneyMap(finance.pendingDeductions(List.of(kolId)));
    List<PaymentLine> rows = new ArrayList<>();
    for (PrepayPaymentRow row :
        finance.prepayPayments(List.of(kolId), filter.start(), filter.end())) {
      rows.add(
          new PaymentLine(
              row.paymentId(),
              row.sourceNo(),
              "PREPAY",
              kolId,
              row.amountCny().toPlainString(),
              row.paidAt(),
              row.deduct() == null
                  ? BigDecimal.ZERO
                  : row.deduct() ? BigDecimal.ONE : BigDecimal.ZERO,
              deducted.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)),
              pending.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)),
              row.amountCny().toPlainString(),
              "CNY",
              row.voucherFileId(),
              null));
    }
    return rows;
  }

  private KolRefDTO requireVisibleKol(AccountDTO user, long kolId) {
    List<KolRefDTO> found = kols.listByIds(List.of(kolId));
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolRefDTO kol = found.getFirst();
    if (user.ownScope() && !java.util.Objects.equals(kol.bdUserId(), user.id())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return kol;
  }

  private static long requireTenant(AccountDTO user) {
    if (user == null || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static Map<Long, BigDecimal> moneyMap(List<KolMoneyRow> rows) {
    Map<Long, BigDecimal> map = new HashMap<>();
    for (KolMoneyRow row : rows) {
      map.put(row.kolId(), row.amountCny() == null ? BigDecimal.ZERO.setScale(2) : row.amountCny());
    }
    return map;
  }

  private static Map<Long, BigDecimal> prepayMoneyMap(List<PrepayPaidRow> rows) {
    Map<Long, BigDecimal> map = new HashMap<>();
    for (PrepayPaidRow row : rows) {
      map.put(row.kolId(), row.amountCny() == null ? BigDecimal.ZERO.setScale(2) : row.amountCny());
    }
    return map;
  }
}
