package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.CommissionSettlementService;
import com.tcrm.admin.commission.service.CommissionWithdrawalService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated KOL commission and withdrawal projections; KOL identity always comes from session.
 */
@RestController
public class KolCommissionController {
  private final CurrentKolContext current;
  private final CommissionSettlementService settlement;
  private final CommissionWithdrawalService withdrawals;
  private final PayoutAccountService payoutAccounts;
  private final JdbcTemplate jdbc;

  public KolCommissionController(
      CurrentKolContext current,
      CommissionSettlementService settlement,
      CommissionWithdrawalService withdrawals,
      PayoutAccountService payoutAccounts,
      JdbcTemplate jdbc) {
    this.current = current;
    this.settlement = settlement;
    this.withdrawals = withdrawals;
    this.payoutAccounts = payoutAccounts;
    this.jdbc = jdbc;
  }

  public record Income(
      String id,
      String name,
      String code,
      String email,
      String bdId,
      String bd,
      int orderCount,
      int quantity,
      String estimated,
      String frozen,
      String available,
      String occupied,
      String cumulativePaid,
      String deducted,
      String pendingDeduction,
      String paid,
      String commissionPaid,
      String prepayPaid,
      String lastPaidAt,
      long unverified) {}

  public record Payment(
      String id,
      String sourceNo,
      String type,
      String kolId,
      String amountCny,
      Instant paidAt,
      boolean deduct,
      String deducted,
      String pending,
      String actualAmount,
      String actualCurrency,
      String voucher,
      String withdrawalId) {}

  public record Detail(
      Income income, List<CommissionSettlementService.ItemLine> lines, List<Payment> payments) {}

  public record Conditions(
      String currency, String minimum, String version, List<Channel> channels) {}

  public record Channel(String code, String name, String basis, int days) {}

  public record FundRecord(
      String id,
      String type,
      Instant appliedAt,
      String applicationNo,
      String amount,
      String currency,
      String maskedRecipient,
      Instant receivedAt,
      String publicStatus,
      String sourceId) {}

  public record WithdrawalQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) {}

  public record ApplyRequest(
      @jakarta.validation.constraints.NotBlank String amount,
      CommissionWithdrawalService.AccountInput account,
      String note,
      Long previousId,
      Long payoutAccountId,
      @jakarta.validation.constraints.NotBlank String idempotencyKey) {}

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/commissions/overview")
  public R<Detail> overview(
      @RequestParam(required = false) Instant start, @RequestParam(required = false) Instant end) {
    KolAccountDTO kol = current.kol();
    if (start != null && end != null && !start.isBefore(end)) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    settlement.ensureOrderItems(kol.tenantId(), kol.id());
    settlement.releaseMatured(kol.tenantId(), kol.id());
    CommissionSettlementService.Summary summary = settlement.summary(kol.tenantId(), kol.id());
    long orders =
        count(
            "SELECT COUNT(DISTINCT o.id) FROM crm_order o WHERE o.tenant_id=? AND o.kol_id=? AND o.is_deleted=FALSE AND o.pay_time IS NOT NULL",
            kol);
    long quantity =
        count(
            "SELECT COALESCE(SUM(i.quantity),0) FROM crm_order_item i JOIN crm_order o ON o.id=i.order_id AND o.tenant_id=i.tenant_id WHERE i.tenant_id=? AND i.kol_id=? AND i.is_deleted=FALSE AND o.is_deleted=FALSE AND o.pay_time IS NOT NULL",
            kol);
    List<Payment> payments = payments(kol, start, end);
    BigDecimal[] totals = paymentTotals(kol, start, end);
    BigDecimal commissionPaid = totals[0];
    BigDecimal prepayPaid = totals[1];
    BigDecimal paid = commissionPaid.add(prepayPaid);
    Instant lastPaid = payments.stream().map(Payment::paidAt).max(Instant::compareTo).orElse(null);
    Income income =
        new Income(
            Long.toString(kol.id()),
            kol.name(),
            kol.kolCode(),
            kol.email(),
            null,
            null,
            Math.toIntExact(Math.min(orders, Integer.MAX_VALUE)),
            Math.toIntExact(Math.min(quantity, Integer.MAX_VALUE)),
            summary.estimated(),
            summary.frozen(),
            summary.available(),
            summary.occupied(),
            summary.cumulativePaid(),
            summary.deducted(),
            summary.pendingDeduction(),
            money(paid),
            money(commissionPaid),
            money(prepayPaid),
            lastPaid == null ? null : lastPaid.toString(),
            summary.unverified());
    List<CommissionSettlementService.ItemLine> lines =
        settlement.lines(kol.tenantId(), kol.id(), 100, 0);
    return R.ok(new Detail(income, lines, payments));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/conditions")
  public R<Conditions> conditions() {
    KolAccountDTO kol = current.kol();
    CommissionSettlementService.Config config = settlement.currentConfig(kol.tenantId());
    return R.ok(
        new Conditions(
            config.currency(),
            config.minimum(),
            config.version(),
            List.of(new Channel("WEIDIAN", "weidian", "freezeBasis", config.days()))));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/recipient-options")
  public R<List<CommissionWithdrawalService.AccountSnapshot>> recipientOptions() {
    KolAccountDTO kol = current.kol();
    return R.ok(
        payoutAccounts.listMine(kol.id()).stream()
            .filter(a -> a.status() == com.tcrm.admin.commission.model.PayoutAccountStatus.APPROVED)
            .map(
                a ->
                    new CommissionWithdrawalService.AccountSnapshot(
                        a.id().toString(),
                        a.accountType().name(),
                        a.account(),
                        a.currency(),
                        a.network() == null ? null : a.network().name(),
                        a.holderName(),
                        a.qrFileId() == null ? null : a.qrFileId().toString()))
            .toList());
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals")
  public R<PageResult<CommissionWithdrawalService.Withdrawal>> withdrawals(
      @Valid @ModelAttribute WithdrawalQuery q) {
    KolAccountDTO kol = current.kol();
    return R.ok(
        withdrawals.pageKol(
            kol, q.page() == null ? 1 : q.page(), q.size() == null ? 20 : q.size()));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/fund-records")
  public R<PageResult<FundRecord>> fundRecords(@Valid @ModelAttribute WithdrawalQuery q) {
    KolAccountDTO kol = current.kol();
    int page = q.page() == null ? 1 : q.page();
    int size = q.size() == null ? 20 : q.size();
    if (page < 1 || size < 1 || size > 100) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String rows =
        "SELECT id,tenant_id,kol_id,'COMMISSION' type,create_time applied_at,application_no,amount_cny amount,'CNY' currency,account_masked masked_recipient,(SELECT paid_at FROM crm_commission_withdrawal_payment p WHERE p.tenant_id=w.tenant_id AND p.withdrawal_id=w.id) received_at,status public_status,id source_id FROM crm_commission_withdrawal w WHERE tenant_id=? AND kol_id=? UNION ALL SELECT x.id,x.tenant_id,x.kol_id,'PREPAY',x.create_time,x.prepay_no,p.pay_amount_cny,'CNY',x.payout_account_masked,p.pay_time,x.status,x.id FROM crm_prepay x JOIN crm_prepay_payment p ON p.tenant_id=x.tenant_id AND p.prepay_id=x.id WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID'";
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM (" + rows + ") f",
            Long.class,
            kol.tenantId(),
            kol.id(),
            kol.tenantId(),
            kol.id());
    List<FundRecord> items =
        jdbc.query(
            "SELECT id,type,applied_at,application_no,amount,currency,masked_recipient,received_at,public_status,source_id FROM ("
                + rows
                + ") f ORDER BY applied_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, index) ->
                new FundRecord(
                    Long.toString(rs.getLong("id")),
                    rs.getString("type"),
                    rs.getTimestamp("applied_at").toInstant(),
                    rs.getString("application_no"),
                    money(rs.getBigDecimal("amount")),
                    rs.getString("currency"),
                    rs.getString("masked_recipient"),
                    rs.getTimestamp("received_at") == null
                        ? null
                        : rs.getTimestamp("received_at").toInstant(),
                    rs.getString("public_status"),
                    Long.toString(rs.getLong("source_id"))),
            kol.tenantId(),
            kol.id(),
            kol.tenantId(),
            kol.id(),
            size,
            (long) (page - 1) * size);
    return R.ok(new PageResult<>(items, total == null ? 0L : total, page, size));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/{id}")
  public R<CommissionWithdrawalService.Withdrawal> withdrawal(@PathVariable @Positive Long id) {
    KolAccountDTO kol = current.kol();
    return R.ok(withdrawals.detail(kol.tenantId(), kol.id(), id, true, false));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PostMapping("/kol-api/withdrawals")
  public R<CommissionWithdrawalService.Withdrawal> apply(@Valid @RequestBody ApplyRequest r) {
    KolAccountDTO kol = current.kol();
    return R.ok(
        withdrawals.apply(
            kol,
            new CommissionWithdrawalService.Request(
                r.amount(),
                r.account(),
                r.note(),
                r.previousId(),
                r.payoutAccountId(),
                r.idempotencyKey())));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/withdrawals/{id}/withdraw")
  public R<CommissionWithdrawalService.Withdrawal> withdraw(@PathVariable @Positive Long id) {
    return R.ok(withdrawals.withdraw(current.kol(), id));
  }

  private List<Payment> payments(KolAccountDTO kol, Instant start, Instant end) {
    String where = " WHERE tenant_id=? AND kol_id=?";
    List<Object> args = new java.util.ArrayList<>(List.of(kol.tenantId(), kol.id()));
    String time = "";
    if (start != null) {
      time += " AND paid_at>=?";
      args.add(Timestamp.from(start));
    }
    if (end != null) {
      time += " AND paid_at<?";
      args.add(Timestamp.from(end));
    }
    String sql =
        "SELECT * FROM (SELECT p.id,w.application_no source_no,'COMMISSION' type,w.kol_id,p.amount_cny,p.paid_at,FALSE deduct,0::numeric deducted,0::numeric pending,p.actual_amount,p.actual_currency,p.voucher_file_id voucher,p.withdrawal_id FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=? UNION ALL SELECT pp.id,pp.prepay_no,'PREPAY' type,pp.kol_id,pay.pay_amount_cny,pay.pay_time,pp.deduct_from_commission,COALESCE((SELECT SUM(r.amount_cny) FROM crm_commission_ledger l JOIN crm_commission_recovery r ON r.tenant_id=l.tenant_id AND r.deduction_ledger_id=l.id WHERE l.tenant_id=pp.tenant_id AND l.source_type='PREPAY' AND l.source_id=pp.id),0),COALESCE((SELECT -l.amount_cny-COALESCE(SUM(r.amount_cny),0) FROM crm_commission_ledger l LEFT JOIN crm_commission_recovery r ON r.tenant_id=l.tenant_id AND r.deduction_ledger_id=l.id WHERE l.tenant_id=pp.tenant_id AND l.source_type='PREPAY' AND l.source_id=pp.id AND l.entry_type='PREPAY_DEDUCT' GROUP BY l.amount_cny),0),NULL,NULL,pay.voucher_file_id,NULL FROM crm_prepay pp JOIN crm_prepay_payment pay ON pay.prepay_id=pp.id AND pay.tenant_id=pp.tenant_id WHERE pp.tenant_id=? AND pp.kol_id=? AND pp.status='PAID') f WHERE TRUE";
    List<Object> queryArgs =
        new java.util.ArrayList<>(List.of(kol.tenantId(), kol.id(), kol.tenantId(), kol.id()));
    String filter = "";
    if (start != null) {
      filter += " AND paid_at>=?";
      queryArgs.add(Timestamp.from(start));
    }
    if (end != null) {
      filter += " AND paid_at<?";
      queryArgs.add(Timestamp.from(end));
    }
    return jdbc.query(
        sql + filter + " ORDER BY paid_at DESC,id DESC LIMIT 100",
        (rs, index) ->
            new Payment(
                Long.toString(rs.getLong("id")),
                rs.getString("source_no"),
                rs.getString("type"),
                Long.toString(rs.getLong("kol_id")),
                money(rs.getBigDecimal("amount_cny")),
                rs.getTimestamp("paid_at").toInstant(),
                rs.getBoolean("deduct"),
                money(rs.getBigDecimal("deducted")),
                money(rs.getBigDecimal("pending")),
                rs.getBigDecimal("actual_amount") == null
                    ? null
                    : rs.getBigDecimal("actual_amount").stripTrailingZeros().toPlainString(),
                rs.getString("actual_currency"),
                rs.getObject("voucher") == null ? null : Long.toString(rs.getLong("voucher")),
                rs.getObject("withdrawal_id") == null
                    ? null
                    : Long.toString(rs.getLong("withdrawal_id"))),
        queryArgs.toArray());
  }

  private BigDecimal[] paymentTotals(KolAccountDTO kol, Instant start, Instant end) {
    String commission =
        "SELECT COALESCE(SUM(p.amount_cny),0) FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=?";
    String prepay =
        "SELECT COALESCE(SUM(p.pay_amount_cny),0) FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID'";
    List<Object> ca = new java.util.ArrayList<>(List.of(kol.tenantId(), kol.id()));
    List<Object> pa = new java.util.ArrayList<>(List.of(kol.tenantId(), kol.id()));
    if (start != null) {
      commission += " AND p.paid_at>=?";
      prepay += " AND p.pay_time>=?";
      ca.add(Timestamp.from(start));
      pa.add(Timestamp.from(start));
    }
    if (end != null) {
      commission += " AND p.paid_at<?";
      prepay += " AND p.pay_time<?";
      ca.add(Timestamp.from(end));
      pa.add(Timestamp.from(end));
    }
    return new BigDecimal[] {
      jdbc.queryForObject(commission, BigDecimal.class, ca.toArray()),
      jdbc.queryForObject(prepay, BigDecimal.class, pa.toArray())
    };
  }

  private long count(String sql, KolAccountDTO kol) {
    Long value = jdbc.queryForObject(sql, Long.class, kol.tenantId(), kol.id());
    return value == null ? 0 : value;
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
