package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.KolMoneyRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaidRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.KolAmountRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.PaidPaymentRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.PaidWindowRow;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator income list and detail (task 62, requirement 11.02, design §6.2): per-KOL money plus
 * period payments. Occupied is the in-flight withdrawal total. Period paid amounts follow the
 * payment window and {@code paymentType} on both the row and the summary.
 */
@Service
public class CommissionIncomeService {
  private static final Comparator<PaymentLine> NEWEST_PAYMENT =
      Comparator.comparing(
              PaymentLine::paidAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
          .thenComparingLong(PaymentLine::id)
          .reversed();

  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final CommissionFinanceQueryMapper finance;
  private final WithdrawalMapper withdrawals;
  private final KolApi kols;
  private final OrderSettlementApi orderSettlement;
  private final ProductAccessApi products;
  private final AccountNameApi accountNames;

  public CommissionIncomeService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      CommissionFinanceQueryMapper finance,
      WithdrawalMapper withdrawals,
      KolApi kols,
      OrderSettlementApi orderSettlement,
      ProductAccessApi products,
      AccountNameApi accountNames) {
    this.states = states;
    this.revisions = revisions;
    this.finance = finance;
    this.withdrawals = withdrawals;
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

  /**
   * Summary over the filtered set, not only the current page. {@code paid} is {@code commissionPaid
   * + prepayPaid}.
   */
  public record Summary(
      BigDecimal paid,
      BigDecimal commissionPaid,
      BigDecimal prepayPaid,
      long creators,
      long orders,
      long quantity,
      long unverified) {}

  /**
   * Commission line of the income detail. {@code product} and {@code sku} come from the order line.
   */
  public record CommissionLine(
      long id,
      String orderNo,
      long itemId,
      String product,
      String sku,
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

  /** Payment row of the income detail: a paid withdrawal or a prepay payment. */
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
    // Ledger sum is cumulative only. The period figure is the payment window below.
    Map<Long, BigDecimal> commissionPaidAll = moneyMap(finance.withdrawalPaidSums(kolIds));
    Map<Long, BigDecimal> prepayPaidAll =
        prepayMoneyMap(finance.prepayPaidSums(kolIds, null, null));
    Map<Long, BigDecimal> occupied = occupiedMap(kolIds);
    PeriodPaid period = periodPaid(kolIds, filter);

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
      BigDecimal held = occupied.getOrDefault(id, zero());
      BigDecimal balance = balances.getOrDefault(id, zero());
      BigDecimal commission = period.commission().getOrDefault(id, zero());
      BigDecimal prepayWindow = period.prepay().getOrDefault(id, zero());
      BigDecimal prepayAll = prepayPaidAll.getOrDefault(id, zero());
      money.put(
          id,
          new KolMoney(
              estimated.getOrDefault(id, zero()),
              frozen.getOrDefault(id, zero()),
              balance.subtract(held),
              held,
              commissionPaidAll.getOrDefault(id, zero()).add(prepayAll),
              deducted.getOrDefault(id, zero()),
              pending.getOrDefault(id, zero()),
              commission.add(prepayWindow),
              commission,
              prepayWindow,
              period.lastPaid().get(id)));
    }
    return new Aggregates(money, orderCounts, quantities, unverified, bdNames);
  }

  private Summary summary(List<Long> kolIds, IncomeFilter filter) {
    BigDecimal zero = BigDecimal.ZERO.setScale(2);
    if (kolIds.isEmpty()) {
      return new Summary(zero, zero, zero, 0, 0, 0, 0);
    }
    PeriodPaid period = periodPaid(kolIds, filter);
    BigDecimal prepayPaid = sum(period.prepay());
    BigDecimal commissionPaid = sum(period.commission());
    long orders = 0;
    long quantity = 0;
    long unverified = 0;
    for (var row : orderSettlement.aggregateKolOrders(kolIds, filter.start(), filter.end())) {
      orders += row.orderCount();
      quantity += row.quantity();
    }
    for (var row : states.aggregateByKol(kolIds)) {
      unverified += row.unverifiedCount() == null ? 0 : row.unverifiedCount();
    }
    return new Summary(
        commissionPaid.add(prepayPaid),
        commissionPaid,
        prepayPaid,
        kolIds.size(),
        orders,
        quantity,
        unverified);
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
              item == null ? null : textOrNull(item.itemName()),
              item == null ? null : textOrNull(item.skuName()),
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
    List<Long> ids = List.of(kolId);
    List<PaymentLine> rows = new ArrayList<>();
    if (scope.includesCommission()) {
      for (PaidPaymentRow row : withdrawals.listPaidPayments(ids, filter.start(), filter.end())) {
        rows.add(
            new PaymentLine(
                row.paymentId(),
                row.applicationNo(),
                "COMMISSION",
                kolId,
                cnyText(row.amountCny()),
                row.paidAt(),
                BigDecimal.ZERO,
                zero(),
                zero(),
                actualText(row.actualAmount()),
                row.actualCurrency(),
                row.voucherFileId(),
                row.withdrawalId()));
      }
    }
    if (scope.includesPrepay()) {
      Map<Long, BigDecimal> deducted = moneyMap(finance.recoverySums(ids));
      Map<Long, BigDecimal> pending = moneyMap(finance.pendingDeductions(ids));
      for (PrepayPaymentRow row : finance.prepayPayments(ids, filter.start(), filter.end())) {
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
                deducted.getOrDefault(kolId, zero()),
                pending.getOrDefault(kolId, zero()),
                row.amountCny().toPlainString(),
                "CNY",
                row.voucherFileId(),
                null));
      }
    }
    rows.sort(NEWEST_PAYMENT);
    return rows;
  }

  /**
   * Period commission and prepay inside the payment window. {@code paymentType} drops the side it
   * does not name. {@code lastPaidAt} is the later payment time of the sides that remain.
   */
  private PeriodPaid periodPaid(List<Long> kolIds, IncomeFilter filter) {
    Map<Long, BigDecimal> commission = new HashMap<>();
    Map<Long, BigDecimal> prepay = new HashMap<>();
    Map<Long, Instant> lastPaid = new HashMap<>();
    if (kolIds.isEmpty()) {
      return new PeriodPaid(commission, prepay, lastPaid);
    }
    PaymentScope scope = PaymentScope.of(filter.paymentType());
    if (scope.includesCommission()) {
      for (PaidWindowRow row : withdrawals.sumPaidInWindow(kolIds, filter.start(), filter.end())) {
        commission.put(row.kolId(), nz(row.amountCny()));
        lastPaid.put(row.kolId(), row.lastTime());
      }
    }
    if (scope.includesPrepay()) {
      for (PrepayPaidRow row : finance.prepayPaidSums(kolIds, filter.start(), filter.end())) {
        prepay.put(row.kolId(), nz(row.amountCny()));
        lastPaid.put(row.kolId(), later(lastPaid.get(row.kolId()), row.lastTime()));
      }
    }
    return new PeriodPaid(commission, prepay, lastPaid);
  }

  private Map<Long, BigDecimal> occupiedMap(List<Long> kolIds) {
    Map<Long, BigDecimal> map = new HashMap<>();
    if (kolIds.isEmpty()) {
      return map;
    }
    for (KolAmountRow row : withdrawals.sumActiveOccupiedByKol(kolIds)) {
      map.put(row.kolId(), nz(row.amountCny()));
    }
    return map;
  }

  private record PeriodPaid(
      Map<Long, BigDecimal> commission,
      Map<Long, BigDecimal> prepay,
      Map<Long, Instant> lastPaid) {}

  private KolRefDTO requireVisibleKol(AccountDTO user, long kolId) {
    List<KolRefDTO> found = kols.listByIds(List.of(kolId));
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolRefDTO kol = found.getFirst();
    if (user.ownScope() && !java.util.Objects.equals(kol.bdUserId(), user.id())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
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
      map.put(row.kolId(), nz(row.amountCny()));
    }
    return map;
  }

  private static BigDecimal sum(Map<Long, BigDecimal> amounts) {
    BigDecimal total = zero();
    for (BigDecimal amount : amounts.values()) {
      total = total.add(nz(amount));
    }
    return total;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? zero() : value;
  }

  private static Instant later(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    if (right == null || !right.isAfter(left)) {
      return left;
    }
    return right;
  }

  /** CNY payment amounts are NUMERIC(14,2). */
  private static String cnyText(BigDecimal value) {
    return nz(value).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  /**
   * Actual paid amount keeps up to 6 decimal places (NUMERIC(20,6)). Trailing zeros collapse, and a
   * whole number still shows two decimal places.
   */
  private static String actualText(BigDecimal value) {
    if (value == null) {
      return null;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() < 2) {
      stripped = stripped.setScale(2);
    }
    return stripped.toPlainString();
  }

  /** Blank order-line names (a line with no SKU) stay null so the client can tell them apart. */
  private static String textOrNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Map<Long, BigDecimal> prepayMoneyMap(List<PrepayPaidRow> rows) {
    Map<Long, BigDecimal> map = new HashMap<>();
    for (PrepayPaidRow row : rows) {
      map.put(row.kolId(), nz(row.amountCny()));
    }
    return map;
  }
}
