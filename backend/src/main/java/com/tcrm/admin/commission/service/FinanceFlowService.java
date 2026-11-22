package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.FinanceFlowMapper;
import com.tcrm.admin.commission.model.FinanceFlowDO;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import com.tcrm.admin.shared.CurrentTenantResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Finance flow ledger writer (task 159, 财务流转口径规则 task 158): appends subject postings to {@code
 * crm_finance_flow} at the business events of the six-stage flow. Append-only and idempotent per
 * source key; commission balances and states stay authoritative in the wallet and the item
 * snapshots, this service only records the subject flows. Income-side postings come from the order
 * facts event ({@link #syncOrderFinance}); commission-side postings are written next to their
 * existing trigger points (revision, release, withdrawal payment).
 */
@Service
public class FinanceFlowService {
  private static final Logger LOG = LoggerFactory.getLogger(FinanceFlowService.class);

  // Income-side subjects (order level).
  public static final String PRE_INCOME = "PRE_INCOME";
  public static final String FROZEN_INCOME = "FROZEN_INCOME";
  public static final String OUTFLOW = "OUTFLOW";
  public static final String WITHDRAWABLE_INCOME = "WITHDRAWABLE_INCOME";

  // Commission-side subjects (order item level except COMMISSION_WITHDRAWN).
  public static final String COMMISSION_FROZEN = "COMMISSION_FROZEN";
  public static final String COMMISSION_CLAWBACK = "COMMISSION_CLAWBACK";
  public static final String COMMISSION_SETTLEABLE = "COMMISSION_SETTLEABLE";
  public static final String ORDER_WITHDRAWN = "ORDER_WITHDRAWN";
  public static final String COMMISSION_WITHDRAWN = "COMMISSION_WITHDRAWN";

  public static final String SOURCE_ORDER = "ORDER";
  public static final String SOURCE_REFUND = "REFUND";
  public static final String SOURCE_SETTLEMENT = "SETTLEMENT";
  public static final String SOURCE_REVISION = "REVISION";
  public static final String SOURCE_RELEASE = "RELEASE";
  public static final String SOURCE_WITHDRAWAL = "WITHDRAWAL";
  public static final String SOURCE_WITHDRAWAL_ALLOCATION = "WITHDRAWAL_ALLOCATION";

  /** Evidence tags of revision-driven commission clawback postings (provisional netting). */
  public static final String EVIDENCE_PROVISIONAL = "PROVISIONAL";

  public static final String EVIDENCE_RESTORE = "RESTORE";
  public static final String EVIDENCE_OWNERSHIP = "OWNERSHIP";
  public static final String EVIDENCE_DECREASE = "DECREASE";

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  private final FinanceFlowMapper flows;
  private final CurrentTenantResolver currentTenant;
  private final Clock clock;

  @Autowired
  public FinanceFlowService(FinanceFlowMapper flows, CurrentTenantResolver currentTenant) {
    this(flows, currentTenant, Clock.systemUTC());
  }

  FinanceFlowService(FinanceFlowMapper flows, CurrentTenantResolver currentTenant, Clock clock) {
    this.flows = flows;
    this.currentTenant = currentTenant;
    this.clock = clock;
  }

  /** One append-only posting; the amount is signed (positive increases the subject). */
  public record Command(
      long tenantId,
      String subject,
      BigDecimal amountCny,
      Instant occurredAt,
      Long orderId,
      Long orderItemId,
      Long kolId,
      String sourceType,
      Long sourceId,
      String sourceKey,
      String evidence) {}

  /**
   * Appends one entry; returns false when the source key already exists, so repeated syncs,
   * repeated settlement pulls and repeated payment registrations never double-post.
   */
  public boolean record(Command command) {
    if (command.amountCny() == null || command.amountCny().signum() == 0) {
      return false;
    }
    if (findBySourceKey(command.tenantId(), command.sourceKey()) != null) {
      return false;
    }
    long id = IdWorker.getId();
    FinanceFlowDO row = new FinanceFlowDO();
    row.setTenantId(command.tenantId());
    row.setOrderId(command.orderId());
    row.setOrderItemId(command.orderItemId());
    row.setKolId(command.kolId());
    row.setSubject(command.subject());
    row.setAmountCny(command.amountCny().setScale(2, java.math.RoundingMode.HALF_UP));
    row.setOccurredAt(command.occurredAt().truncatedTo(ChronoUnit.MILLIS));
    row.setSourceType(command.sourceType());
    row.setSourceId(command.sourceId());
    row.setSourceKey(command.sourceKey());
    row.setFlowNo("FF" + id);
    row.setEvidence(command.evidence());
    row.setId(id);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    row.setCreateTime(now);
    row.setUpdateTime(now);
    try {
      flows.insert(row);
      return true;
    } catch (DuplicateKeyException conflict) {
      // A concurrent sync posted the same fact first; the entry exists exactly once.
      return false;
    }
  }

  public FinanceFlowDO findBySourceKey(long tenantId, String sourceKey) {
    return flows.findBySourceKey(tenantId, sourceKey);
  }

  /** Every posting of one order, business time ascending (chain 财务流水 listing). */
  public List<FinanceFlowDO> listByOrder(long tenantId, long orderId) {
    return flows.selectByOrder(tenantId, orderId);
  }

  /** Signed balance of one subject of one order (Σ postings). */
  public BigDecimal balanceByOrderAndSubject(long tenantId, long orderId, String subject) {
    return listByOrder(tenantId, orderId).stream()
        .filter(row -> subject.equals(row.getSubject()))
        .map(FinanceFlowDO::getAmountCny)
        .reduce(ZERO, BigDecimal::add);
  }

  /**
   * Net provisional clawback of one line: the after-sales hold postings minus their restorations. A
   * later accrual increase first nets this down before counting as new accrual.
   */
  public BigDecimal provisionalClawbackNet(long tenantId, long orderItemId) {
    return flows.selectClawbackProvisional(tenantId, orderItemId).stream()
        .map(FinanceFlowDO::getAmountCny)
        .reduce(ZERO, BigDecimal::add);
  }

  /**
   * Income-side postings of one order sync (task 158 §2.1): pre-income at unpaid registration, its
   * reversal and frozen income at payment, outflow plus income deduction per successful refund, the
   * settlement transfer of the frozen balance into withdrawable income, and the full-refund-style
   * postings of a paid close. Every posting is keyed per business fact, so a repeated sync only
   * fills what earlier rounds missed.
   */
  public void syncOrderFinance(OrderItemsCommissionEvent event) {
    long tenantId = requireTenant();
    long orderId = event.orderId();
    OrderItemsCommissionEvent.OrderFacts order = event.order();
    Instant paidAt = event.payTime();
    if (paidAt == null) {
      if ("CLOSED".equals(order.orderStatus())) {
        cancelPreIncome(tenantId, orderId, order.orderUpdatedAt());
      } else if ("UNPAID".equals(order.orderStatus())
          && order.orderTotalCny() != null
          && order.orderTotalCny().signum() > 0) {
        record(
            new Command(
                tenantId,
                PRE_INCOME,
                order.orderTotalCny(),
                order.orderTime(),
                orderId,
                null,
                null,
                SOURCE_ORDER,
                orderId,
                "FIN:" + orderId + ":PRE_INCOME",
                "下单未支付，订单金额＝商品总价＋运费"));
      }
      return;
    }
    FinanceFlowDO pre = findBySourceKey(tenantId, preIncomeKey(orderId));
    if (pre != null && pre.getAmountCny().signum() != 0) {
      record(
          new Command(
              tenantId,
              PRE_INCOME,
              pre.getAmountCny().negate(),
              paidAt,
              orderId,
              null,
              null,
              SOURCE_ORDER,
              orderId,
              "FIN:" + orderId + ":PRE_INCOME_REVERSAL",
              "支付核销预收入"));
    }
    record(
        new Command(
            tenantId,
            FROZEN_INCOME,
            order.payAmount(),
            paidAt,
            orderId,
            null,
            null,
            SOURCE_ORDER,
            orderId,
            "FIN:" + orderId + ":FROZEN_INCOME",
            "订单支付，计入冻结中收入"));
    for (OrderItemsCommissionEvent.RefundFacts refund : order.refunds()) {
      if (!"SUCCESS".equals(refund.refundStatus())
          || refund.amount() == null
          || refund.amount().signum() <= 0) {
        continue;
      }
      record(
          new Command(
              tenantId,
              OUTFLOW,
              refund.amount(),
              refund.occurredAt(),
              orderId,
              null,
              null,
              SOURCE_REFUND,
              refund.refundId(),
              "FIN:" + refund.refundId() + ":OUTFLOW",
              "退款成功，计入支出"));
      // One refund fact books one deduction unit. The split across the two income subjects is
      // fixed by the first write; a replay after the balances moved must not deduct again through
      // the other subject (task 159 review — the two split keys alone do not deduplicate it).
      if (findBySourceKey(tenantId, "FIN:" + refund.refundId() + ":INCOME_DEDUCT_FROZEN") != null
          || findBySourceKey(tenantId, "FIN:" + refund.refundId() + ":INCOME_DEDUCT_WITHDRAWABLE")
              != null) {
        continue;
      }
      // §2.1: pre-settlement refunds reduce frozen income, post-settlement ones reduce withdrawable
      // income. A pre-settlement refund first seen after the transfer (out-of-order sync) falls
      // back to the remaining frozen balance and then withdrawable income, keeping the §2.2
      // identity.
      boolean postSettlement =
          order.settledTime() != null && !refund.occurredAt().isBefore(order.settledTime());
      BigDecimal fromFrozen =
          postSettlement
              ? ZERO
              : refund
                  .amount()
                  .min(balanceByOrderAndSubject(tenantId, orderId, FROZEN_INCOME).max(ZERO));
      BigDecimal fromWithdrawable = refund.amount().subtract(fromFrozen);
      if (fromFrozen.signum() > 0) {
        record(
            new Command(
                tenantId,
                FROZEN_INCOME,
                fromFrozen.negate(),
                refund.occurredAt(),
                orderId,
                null,
                null,
                SOURCE_REFUND,
                refund.refundId(),
                "FIN:" + refund.refundId() + ":INCOME_DEDUCT_FROZEN",
                "退款成功，扣减冻结中收入"));
      }
      if (fromWithdrawable.signum() > 0) {
        record(
            new Command(
                tenantId,
                WITHDRAWABLE_INCOME,
                fromWithdrawable.negate(),
                refund.occurredAt(),
                orderId,
                null,
                null,
                SOURCE_REFUND,
                refund.refundId(),
                "FIN:" + refund.refundId() + ":INCOME_DEDUCT_WITHDRAWABLE",
                "退款成功，扣减可提现收入"));
      }
    }
    if (order.settledTime() != null) {
      // Transfer the frozen balance (pay amount less pre-settle refund deductions), not the raw
      // goods-income figure: the two coincide only under the confirmed gross/net reading of M-1,
      // and the balance transfer keeps the §2.2 identity exact either way. The flow amount is
      // recorded for reconciliation.
      BigDecimal frozen = balanceByOrderAndSubject(tenantId, orderId, FROZEN_INCOME);
      if (frozen.signum() > 0) {
        String evidence =
            "结算确认转可提现收入（货款收入＝"
                + (order.settlementIncomeCny() == null
                    ? "未知"
                    : order.settlementIncomeCny().toPlainString())
                + "）";
        record(
            new Command(
                tenantId,
                FROZEN_INCOME,
                frozen.negate(),
                order.settledTime(),
                orderId,
                null,
                null,
                SOURCE_SETTLEMENT,
                orderId,
                "FIN:" + orderId + ":SETTLE_FROM",
                evidence));
        record(
            new Command(
                tenantId,
                WITHDRAWABLE_INCOME,
                frozen,
                order.settledTime(),
                orderId,
                null,
                null,
                SOURCE_SETTLEMENT,
                orderId,
                "FIN:" + orderId + ":SETTLE_TO",
                evidence));
      }
    }
    if ("CLOSED".equals(order.orderStatus())) {
      BigDecimal remaining =
          balanceByOrderAndSubject(tenantId, orderId, FROZEN_INCOME)
              .add(balanceByOrderAndSubject(tenantId, orderId, WITHDRAWABLE_INCOME));
      if (remaining.signum() > 0) {
        record(
            new Command(
                tenantId,
                OUTFLOW,
                remaining,
                order.orderUpdatedAt(),
                orderId,
                null,
                null,
                SOURCE_ORDER,
                orderId,
                "FIN:" + orderId + ":CLOSE_OUTFLOW",
                "已支付关单，剩余收入计入支出"));
        String incomeSubject =
            balanceByOrderAndSubject(tenantId, orderId, WITHDRAWABLE_INCOME).signum() > 0
                ? WITHDRAWABLE_INCOME
                : FROZEN_INCOME;
        record(
            new Command(
                tenantId,
                incomeSubject,
                remaining.negate(),
                order.orderUpdatedAt(),
                orderId,
                null,
                null,
                SOURCE_ORDER,
                orderId,
                "FIN:" + orderId + ":CLOSE_INCOME_DEDUCT",
                "已支付关单，清减收入科目"));
      }
    }
  }

  private void cancelPreIncome(long tenantId, long orderId, Instant occurredAt) {
    FinanceFlowDO pre = findBySourceKey(tenantId, preIncomeKey(orderId));
    if (pre == null || pre.getAmountCny().signum() == 0) {
      return;
    }
    record(
        new Command(
            tenantId,
            PRE_INCOME,
            pre.getAmountCny().negate(),
            occurredAt,
            orderId,
            null,
            null,
            SOURCE_ORDER,
            orderId,
            "FIN:" + orderId + ":PRE_INCOME_CANCEL",
            "未支付关单，核销预收入"));
  }

  private static String preIncomeKey(long orderId) {
    return "FIN:" + orderId + ":PRE_INCOME";
  }

  private long requireTenant() {
    Long tenant = com.tcrm.admin.shared.TenantScope.companyId();
    if (tenant == null) {
      tenant = currentTenant.currentLoginTenantId();
    }
    if (tenant == null) {
      throw new IllegalStateException("Finance flow sync requires a company scope");
    }
    return tenant;
  }
}
