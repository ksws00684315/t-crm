package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.PrepayService.Deduction;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** KOL-facing prepay views are a projection of paid records only. */
@RestController
public class KolPrepayController {
  private final JdbcTemplate jdbc;
  private final CurrentKolContext current;

  public KolPrepayController(JdbcTemplate jdbc, CurrentKolContext current) {
    this.jdbc = jdbc;
    this.current = current;
  }

  public record PaidPrepay(
      Long id,
      String prepayNo,
      String status,
      String payAmountCny,
      String payCurrency,
      java.time.Instant payTime,
      String payMethod,
      Long voucherFileId,
      Boolean deductFromCommission,
      Deduction deduction,
      String walletBalanceCny) {}

  public record LedgerRow(
      Long entryId,
      Long prepayId,
      String prepayNo,
      String entryType,
      String amountCny,
      java.time.Instant occurredAt,
      String balanceAfterCny) {}

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays")
  public R<PageResult<PaidPrepay>> list(@ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    int page = query.pageNumber();
    int size = query.pageSize();
    validatePage(page, size);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM crm_prepay WHERE tenant_id = ? AND kol_id = ? AND status = 'PAID'",
            Long.class,
            kol.tenantId(),
            kol.id());
    List<Long> ids =
        jdbc.query(
            "SELECT id FROM crm_prepay WHERE tenant_id = ? AND kol_id = ? AND status = 'PAID' ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
            (rs, index) -> rs.getLong(1),
            kol.tenantId(),
            kol.id(),
            size,
            (long) (page - 1) * size);
    return R.ok(
        new PageResult<>(
            ids.stream().map(id -> paidPrepay(kol, id)).toList(),
            total == null ? 0L : total,
            page,
            size));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/{id}")
  public R<PaidPrepay> detail(@PathVariable @Positive Long id) {
    KolAccountDTO kol = current.kol();
    if (!paid(kol, id)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return R.ok(paidPrepay(kol, id));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/ledger")
  public R<PageResult<LedgerRow>> ledger(@ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    int page = query.pageNumber();
    int size = query.pageSize();
    validatePage(page, size);
    String source =
        " FROM ("
            + " SELECT p.tenant_id, p.kol_id, p.id AS prepay_id, p.prepay_no, payment.id AS entry_id, 'PREPAY_PAID' AS entry_type, payment.pay_amount_cny AS amount_cny, payment.pay_time AS occurred_at, NULL::numeric AS balance_after_cny FROM crm_prepay p JOIN crm_prepay_payment payment ON payment.tenant_id = p.tenant_id AND payment.prepay_id = p.id WHERE p.status = 'PAID'"
            + " UNION ALL"
            + " SELECT p.tenant_id, p.kol_id, p.id AS prepay_id, p.prepay_no, l.id AS entry_id, 'PREPAY_DEDUCT' AS entry_type, l.amount_cny, l.occurred_at, l.balance_after_cny FROM crm_prepay p JOIN crm_commission_ledger l ON l.tenant_id = p.tenant_id AND l.source_type = 'PREPAY' AND l.source_id = p.id AND l.entry_type = 'PREPAY_DEDUCT' WHERE p.status = 'PAID'"
            + " ) flow WHERE tenant_id = ? AND kol_id = ?";
    Long total =
        jdbc.queryForObject("SELECT count(*)" + source, Long.class, kol.tenantId(), kol.id());
    List<LedgerRow> rows =
        jdbc.query(
            "SELECT entry_id, prepay_id, prepay_no, entry_type, amount_cny, occurred_at, balance_after_cny"
                + source
                + " ORDER BY occurred_at DESC, entry_id DESC LIMIT ? OFFSET ?",
            (rs, index) ->
                new LedgerRow(
                    rs.getLong("entry_id"),
                    rs.getLong("prepay_id"),
                    rs.getString("prepay_no"),
                    rs.getString("entry_type"),
                    rs.getBigDecimal("amount_cny").setScale(2).toPlainString(),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getBigDecimal("balance_after_cny") == null
                        ? null
                        : rs.getBigDecimal("balance_after_cny").setScale(2).toPlainString()),
            kol.tenantId(),
            kol.id(),
            size,
            (long) (page - 1) * size);
    return R.ok(new PageResult<>(rows, total == null ? 0L : total, page, size));
  }

  private PaidPrepay paidPrepay(KolAccountDTO kol, long id) {
    return jdbc.queryForObject(
        "SELECT p.id, p.prepay_no, p.deduct_from_commission, payment.pay_amount_cny, payment.pay_time, payment.pay_method, payment.voucher_file_id, wallet.balance_cny, l.id AS deduction_id FROM crm_prepay p JOIN crm_prepay_payment payment ON payment.tenant_id = p.tenant_id AND payment.prepay_id = p.id LEFT JOIN crm_commission_wallet wallet ON wallet.tenant_id = p.tenant_id AND wallet.kol_id = p.kol_id LEFT JOIN crm_commission_ledger l ON l.tenant_id = p.tenant_id AND l.source_type = 'PREPAY' AND l.source_id = p.id AND l.entry_type = 'PREPAY_DEDUCT' WHERE p.tenant_id = ? AND p.kol_id = ? AND p.id = ? AND p.status = 'PAID'",
        (rs, index) -> {
          Boolean deduct = (Boolean) rs.getObject("deduct_from_commission");
          Deduction deduction = null;
          if (Boolean.TRUE.equals(deduct) && rs.getObject("deduction_id") != null) {
            BigDecimal posted = rs.getBigDecimal("pay_amount_cny");
            BigDecimal recovered =
                jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount_cny), 0) FROM crm_commission_recovery WHERE tenant_id = ? AND deduction_ledger_id = ?",
                    BigDecimal.class,
                    kol.tenantId(),
                    rs.getLong("deduction_id"));
            deduction =
                new Deduction(
                    posted.setScale(2).toPlainString(),
                    recovered.setScale(2).toPlainString(),
                    posted.subtract(recovered).setScale(2).toPlainString());
          }
          BigDecimal balance = rs.getBigDecimal("balance_cny");
          return new PaidPrepay(
              rs.getLong("id"),
              rs.getString("prepay_no"),
              "PAID",
              rs.getBigDecimal("pay_amount_cny").setScale(2).toPlainString(),
              "CNY",
              rs.getTimestamp("pay_time").toInstant(),
              rs.getString("pay_method"),
              rs.getLong("voucher_file_id"),
              deduct,
              deduction,
              balance == null ? "0.00" : balance.setScale(2).toPlainString());
        },
        kol.tenantId(),
        kol.id(),
        id);
  }

  private boolean paid(KolAccountDTO kol, long id) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM crm_prepay WHERE tenant_id = ? AND kol_id = ? AND id = ? AND status = 'PAID'",
            Long.class,
            kol.tenantId(),
            kol.id(),
            id);
    return count != null && count == 1;
  }

  private static void validatePage(int page, int size) {
    if (page < 1 || size < 1 || size > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  public record PageQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }
}
