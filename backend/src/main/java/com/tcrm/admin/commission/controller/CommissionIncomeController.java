package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.service.CommissionSettlementService;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.R;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Company and current-BD-scope income projections backed by commission ledger state. */
@RestController
public class CommissionIncomeController {
  private final JdbcTemplate jdbc;
  private final CurrentUserContext current;
  private final KolApi kols;
  private final CommissionSettlementService settlement;

  public CommissionIncomeController(
      JdbcTemplate jdbc,
      CurrentUserContext current,
      KolApi kols,
      CommissionSettlementService settlement) {
    this.jdbc = jdbc;
    this.current = current;
    this.kols = kols;
    this.settlement = settlement;
  }

  public record Query(
      String keyword, String paymentType, Instant start, Instant end, Integer page, Integer size) {}

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
      Instant lastPaidAt,
      long unverified) {}

  public record Summary(
      String paid,
      String commissionPaid,
      String prepayPaid,
      int creators,
      int orders,
      int quantity,
      long unverified) {}

  public record Result(List<Income> items, String total, int page, int size, Summary summary) {}

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

  @GetMapping("/admin-api/commission/incomes")
  @SaCheckPermission("commission:view")
  public R<Result> list(@ModelAttribute Query query) {
    AccountDTO user = current.user();
    long tenant = tenant(user);
    int page = query.page() == null ? 1 : query.page();
    int size = query.size() == null ? 20 : query.size();
    validate(query, page, size);
    List<Long> visible = visibleKols(user);
    if (visible != null && visible.isEmpty()) {
      return R.ok(
          new Result(List.of(), "0", page, size, new Summary("0.00", "0.00", "0.00", 0, 0, 0, 0)));
    }
    StringBuilder where = new StringBuilder(" WHERE k.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" AND (k.name ILIKE ? OR k.kol_code ILIKE ? OR k.email ILIKE ?)");
      String term = "%" + query.keyword().strip() + "%";
      args.add(term);
      args.add(term);
      args.add(term);
    }
    appendPaymentTypeFilter(query, tenant, where, args);
    if (visible != null) {
      where
          .append(" AND k.id IN (")
          .append(String.join(",", java.util.Collections.nCopies(visible.size(), "?")))
          .append(')');
      args.addAll(visible);
    }
    Long total =
        jdbc.queryForObject("SELECT count(*) FROM crm_kol k" + where, args.toArray(), Long.class);
    List<Long> ids =
        jdbc.query(
            "SELECT k.id FROM crm_kol k" + where + " ORDER BY k.id LIMIT ? OFFSET ?",
            (rs, index) -> rs.getLong(1),
            append(args, size, (long) (page - 1) * size));
    List<Income> items =
        ids.stream()
            .map(id -> income(tenant, id, query.start(), query.end()))
            .filter(x -> x != null)
            .toList();
    List<Income> all =
        jdbc.query(
            "SELECT k.id FROM crm_kol k" + where,
            (rs, index) -> income(tenant, rs.getLong(1), query.start(), query.end()),
            args.toArray());
    BigDecimal commission =
        all.stream()
            .map(i -> new BigDecimal(i.commissionPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal prepay =
        all.stream()
            .map(i -> new BigDecimal(i.prepayPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    int orders = all.stream().mapToInt(Income::orderCount).sum();
    int qty = all.stream().mapToInt(Income::quantity).sum();
    long unverified = all.stream().mapToLong(Income::unverified).sum();
    return R.ok(
        new Result(
            items,
            Long.toString(total == null ? 0 : total),
            page,
            size,
            new Summary(
                money(commission.add(prepay)),
                money(commission),
                money(prepay),
                all.size(),
                orders,
                qty,
                unverified)));
  }

  @GetMapping("/admin-api/commission/incomes/{id}")
  @SaCheckPermission("commission:view")
  public R<Detail> detail(
      @PathVariable Long id,
      @RequestParam(required = false) String paymentType,
      @RequestParam(required = false) Instant start,
      @RequestParam(required = false) Instant end) {
    AccountDTO user = current.user();
    long tenant = tenant(user);
    validateDates(start, end);
    if (!inScope(user, id)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (paymentType != null && !List.of("COMMISSION", "PREPAY").contains(paymentType)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Income income = income(tenant, id, start, end);
    if (income == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    Long lineCount =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM crm_commission_item_revision r JOIN crm_commission_item_state s ON s.id=r.item_state_id AND s.tenant_id=r.tenant_id WHERE r.tenant_id=? AND s.kol_id=?)+(SELECT count(*) FROM crm_commission_item_state s WHERE s.tenant_id=? AND s.kol_id=? AND NOT EXISTS(SELECT 1 FROM crm_commission_item_revision r WHERE r.tenant_id=s.tenant_id AND r.item_state_id=s.id))",
            Long.class,
            tenant,
            id,
            tenant,
            id);
    Long paymentCount =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=?)+(SELECT count(*) FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID')",
            Long.class,
            tenant,
            id,
            tenant,
            id);
    if (lineCount > 10000 || paymentCount > 10000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<Payment> payments = payments(tenant, id, start, end, paymentType);
    return R.ok(new Detail(income, settlement.lines(tenant, id, 10000, 0), payments));
  }

  private Income income(long tenant, long kolId, Instant start, Instant end) {
    List<Income> row =
        jdbc.query(
            "SELECT k.id,k.name,k.kol_code,k.email,k.bd_user_id,u.nickname FROM crm_kol k LEFT JOIN sys_user u ON u.id=k.bd_user_id WHERE k.tenant_id=? AND k.id=?",
            (rs, index) -> {
              CommissionSettlementService.Summary s = settlement.summary(tenant, kolId);
              BigDecimal[] paid = paidAmounts(tenant, kolId, start, end);
              Long orders =
                  jdbc.queryForObject(
                      "SELECT count(DISTINCT o.id) FROM crm_order o WHERE o.tenant_id=? AND o.kol_id=? AND o.pay_time IS NOT NULL AND (? IS NULL OR o.pay_time>=?) AND (? IS NULL OR o.pay_time<?)",
                      Long.class,
                      tenant,
                      kolId,
                      start == null ? null : Timestamp.from(start),
                      start == null ? null : Timestamp.from(start),
                      end == null ? null : Timestamp.from(end),
                      end == null ? null : Timestamp.from(end));
              Long quantity =
                  jdbc.queryForObject(
                      "SELECT COALESCE(SUM(i.quantity),0) FROM crm_order_item i JOIN crm_order o ON o.id=i.order_id AND o.tenant_id=i.tenant_id WHERE i.tenant_id=? AND i.kol_id=? AND o.pay_time IS NOT NULL AND (? IS NULL OR o.pay_time>=?) AND (? IS NULL OR o.pay_time<?)",
                      Long.class,
                      tenant,
                      kolId,
                      start == null ? null : Timestamp.from(start),
                      start == null ? null : Timestamp.from(start),
                      end == null ? null : Timestamp.from(end),
                      end == null ? null : Timestamp.from(end));
              Timestamp last =
                  jdbc.queryForObject(
                      "SELECT max(paid_at) FROM (SELECT p.paid_at FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=? UNION ALL SELECT p.pay_time FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID') p WHERE (? IS NULL OR paid_at>=?) AND (? IS NULL OR paid_at<?)",
                      Timestamp.class,
                      tenant,
                      kolId,
                      tenant,
                      kolId,
                      start == null ? null : Timestamp.from(start),
                      start == null ? null : Timestamp.from(start),
                      end == null ? null : Timestamp.from(end),
                      end == null ? null : Timestamp.from(end));
              return new Income(
                  Long.toString(rs.getLong(1)),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getObject(5) == null ? null : rs.getString(5),
                  rs.getString(6),
                  orders == null ? 0 : orders.intValue(),
                  quantity == null ? 0 : quantity.intValue(),
                  s.estimated(),
                  s.frozen(),
                  s.available(),
                  s.occupied(),
                  s.cumulativePaid(),
                  s.deducted(),
                  s.pendingDeduction(),
                  money(paid[0].add(paid[1])),
                  money(paid[0]),
                  money(paid[1]),
                  last == null ? null : last.toInstant(),
                  s.unverified());
            },
            tenant,
            kolId);
    return row.isEmpty() ? null : row.getFirst();
  }

  private BigDecimal[] paidAmounts(long tenant, long kol, Instant start, Instant end) {
    String c =
        "SELECT COALESCE(SUM(p.amount_cny),0) FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=?";
    String a =
        "SELECT COALESCE(SUM(p.pay_amount_cny),0) FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID'";
    List<Object> ca = new ArrayList<>(List.of(tenant, kol));
    List<Object> aa = new ArrayList<>(List.of(tenant, kol));
    if (start != null) {
      c += " AND p.paid_at>=?";
      a += " AND p.pay_time>=?";
      ca.add(Timestamp.from(start));
      aa.add(Timestamp.from(start));
    }
    if (end != null) {
      c += " AND p.paid_at<?";
      a += " AND p.pay_time<?";
      ca.add(Timestamp.from(end));
      aa.add(Timestamp.from(end));
    }
    return new BigDecimal[] {
      jdbc.queryForObject(c, BigDecimal.class, ca.toArray()),
      jdbc.queryForObject(a, BigDecimal.class, aa.toArray())
    };
  }

  private List<Payment> payments(long tenant, long kol, Instant start, Instant end, String type) {
    List<Payment> out = new ArrayList<>();
    if (type == null || "COMMISSION".equals(type)) {
      out.addAll(
          jdbc.query(
              "SELECT p.id,w.application_no,p.amount_cny,p.paid_at,p.actual_amount,p.actual_currency,p.voucher_file_id,w.id FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=? AND w.kol_id=? AND (? IS NULL OR p.paid_at>=?) AND (? IS NULL OR p.paid_at<?) ORDER BY p.paid_at DESC,p.id DESC",
              (rs, index) ->
                  new Payment(
                      Long.toString(rs.getLong(1)),
                      rs.getString(2),
                      "COMMISSION",
                      Long.toString(kol),
                      money(rs.getBigDecimal(3)),
                      rs.getTimestamp(4).toInstant(),
                      false,
                      "0.00",
                      "0.00",
                      rs.getBigDecimal(5).stripTrailingZeros().toPlainString(),
                      rs.getString(6),
                      Long.toString(rs.getLong(7)),
                      Long.toString(rs.getLong(8))),
              tenant,
              kol,
              start == null ? null : Timestamp.from(start),
              start == null ? null : Timestamp.from(start),
              end == null ? null : Timestamp.from(end),
              end == null ? null : Timestamp.from(end)));
    }
    if (type == null || "PREPAY".equals(type)) {
      out.addAll(
          jdbc.query(
              "SELECT p.id,x.prepay_no,p.pay_amount_cny,p.pay_time,x.deduct_from_commission,p.voucher_file_id,x.id,COALESCE(-d.amount_cny,0) deducted,COALESCE(-d.amount_cny-COALESCE((SELECT SUM(r.amount_cny) FROM crm_commission_recovery r WHERE r.tenant_id=d.tenant_id AND r.deduction_ledger_id=d.id),0),0) pending FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id LEFT JOIN crm_commission_ledger d ON d.tenant_id=x.tenant_id AND d.kol_id=x.kol_id AND d.source_type='PREPAY' AND d.source_id=x.id AND d.entry_type='PREPAY_DEDUCT' WHERE x.tenant_id=? AND x.kol_id=? AND x.status='PAID' AND (? IS NULL OR p.pay_time>=?) AND (? IS NULL OR p.pay_time<?) ORDER BY p.pay_time DESC,p.id DESC",
              (rs, index) ->
                  new Payment(
                      Long.toString(rs.getLong(1)),
                      rs.getString(2),
                      "PREPAY",
                      Long.toString(kol),
                      money(rs.getBigDecimal(3)),
                      rs.getTimestamp(4).toInstant(),
                      rs.getBoolean(5),
                      money(rs.getBigDecimal("deducted")),
                      money(rs.getBigDecimal("pending")),
                      null,
                      null,
                      Long.toString(rs.getLong(6)),
                      Long.toString(rs.getLong(7))),
              tenant,
              kol,
              start == null ? null : Timestamp.from(start),
              start == null ? null : Timestamp.from(start),
              end == null ? null : Timestamp.from(end),
              end == null ? null : Timestamp.from(end)));
    }
    out.sort(java.util.Comparator.comparing(Payment::paidAt).reversed().thenComparing(Payment::id));
    return out;
  }

  private List<Long> visibleKols(AccountDTO user) {
    if (!user.ownScope()) {
      return null;
    }
    return kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).distinct().toList();
  }

  private boolean inScope(AccountDTO user, long kol) {
    List<Long> ids = visibleKols(user);
    return ids == null || ids.contains(kol);
  }

  private static long tenant(AccountDTO user) {
    if (user.tenantId() == null || user.tenantId() <= 0) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static Object[] append(List<Object> values, Object... tail) {
    List<Object> args = new ArrayList<>(values);
    args.addAll(List.of(tail));
    return args.toArray();
  }

  private static void appendPaymentTypeFilter(
      Query query, long tenant, StringBuilder where, List<Object> args) {
    if ("COMMISSION".equals(query.paymentType())) {
      where.append(
          " AND EXISTS(SELECT 1 FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE w.tenant_id=k.tenant_id AND w.kol_id=k.id");
      addDateFilter(where, args, "p.paid_at", query.start(), query.end());
      where.append(')');
    } else if ("PREPAY".equals(query.paymentType())) {
      where.append(
          " AND EXISTS(SELECT 1 FROM crm_prepay_payment p JOIN crm_prepay x ON x.id=p.prepay_id AND x.tenant_id=p.tenant_id WHERE x.tenant_id=k.tenant_id AND x.kol_id=k.id AND x.status='PAID'");
      addDateFilter(where, args, "p.pay_time", query.start(), query.end());
      where.append(')');
    }
  }

  private static void addDateFilter(
      StringBuilder where, List<Object> args, String column, Instant start, Instant end) {
    if (start != null) {
      where.append(" AND ").append(column).append(">=?");
      args.add(Timestamp.from(start));
    }
    if (end != null) {
      where.append(" AND ").append(column).append("<?");
      args.add(Timestamp.from(end));
    }
  }

  private static void validate(Query q, int page, int size) {
    if (page < 1
        || page > 10000
        || size < 1
        || size > 100
        || (q.paymentType() != null
            && !List.of("COMMISSION", "PREPAY").contains(q.paymentType()))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    validateDates(q.start(), q.end());
  }

  private static void validateDates(Instant start, Instant end) {
    if (start != null && end != null && !start.isBefore(end)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
