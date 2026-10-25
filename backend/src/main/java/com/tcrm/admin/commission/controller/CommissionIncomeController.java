package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.service.CommissionIncomeService;
import com.tcrm.admin.commission.service.CommissionIncomeService.CommissionLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeDetail;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeFilter;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomePage;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeRow;
import com.tcrm.admin.commission.service.CommissionIncomeService.PaymentLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.Summary;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator income endpoints (task 62, requirement 11.02, design §6.2): {@code commission:view}; BD
 * OWN scope only sees their own KOLs (service-side check).
 */
@RestController
public class CommissionIncomeController {
  private final CommissionIncomeService incomes;
  private final CurrentUserContext current;

  public CommissionIncomeController(CommissionIncomeService incomes, CurrentUserContext current) {
    this.incomes = incomes;
    this.current = current;
  }

  @SaCheckPermission("commission:view")
  @GetMapping("/admin-api/commission/incomes")
  public R<IncomeResultVO> list(@Valid @ModelAttribute Query query) {
    AccountDTO user = current.user();
    IncomePage page =
        incomes.list(
            user,
            new IncomeFilter(
                query.keyword(),
                query.paymentType(),
                instant(query.start()),
                instant(query.end()),
                query.pageNumber(),
                query.pageSize()));
    return R.ok(IncomeResultVO.from(page));
  }

  @SaCheckPermission("commission:view")
  @GetMapping("/admin-api/commission/incomes/{id}")
  public R<IncomeDetailVO> detail(
      @PathVariable @Positive Long id, @Valid @ModelAttribute Query query) {
    AccountDTO user = current.user();
    IncomeDetail detail =
        incomes.detail(
            user,
            id,
            new IncomeFilter(
                query.keyword(),
                query.paymentType(),
                instant(query.start()),
                instant(query.end()),
                query.pageNumber(),
                query.pageSize()));
    return R.ok(IncomeDetailVO.from(detail));
  }

  /** Shared list/detail query: payment window is inclusive start, exclusive end (ISO 8601 UTC). */
  public record Query(
      @Size(max = 100) String keyword,
      @Pattern(regexp = "COMMISSION|PREPAY|") String paymentType,
      @Size(max = 40) String start,
      @Size(max = 40) String end,
      @Min(1) Integer page,
      @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  /**
   * Flat list envelope (ADR-032 appendix A {@code IncomeResult}): page fields sit beside {@code
   * summary}. Counts are JSON numbers; ids and amounts stay strings.
   */
  public record IncomeResultVO(
      List<IncomeVO> items, int total, int page, int size, SummaryVO summary) {
    static IncomeResultVO from(IncomePage result) {
      PageResult<IncomeRow> page = result.page();
      return new IncomeResultVO(
          page.items().stream().map(IncomeVO::from).toList(),
          count(page.total()),
          page.page(),
          page.size(),
          SummaryVO.from(result.summary()));
    }
  }

  /** One income row (ADR-032 appendix A {@code Income}). */
  public record IncomeVO(
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
      int unverified) {
    static IncomeVO from(IncomeRow row) {
      return new IncomeVO(
          String.valueOf(row.kolId()),
          row.name(),
          row.kolCode(),
          row.email(),
          row.bdId() == null ? null : String.valueOf(row.bdId()),
          row.bd(),
          count(row.orderCount()),
          count(row.quantity()),
          money(row.money().estimated()),
          money(row.money().frozen()),
          money(row.money().available()),
          money(row.money().occupied()),
          money(row.money().cumulativePaid()),
          money(row.money().deducted()),
          money(row.money().pendingDeduction()),
          money(row.money().periodPaid()),
          money(row.money().commissionPaid()),
          money(row.money().prepayPaid()),
          row.money().lastPaidAt() == null ? null : row.money().lastPaidAt().toString(),
          count(row.unverified()));
    }
  }

  /** Summary row of the list (11.02). {@code paid} is commission plus prepay. */
  public record SummaryVO(
      String paid,
      String commissionPaid,
      String prepayPaid,
      int creators,
      int orders,
      int quantity,
      int unverified) {
    static SummaryVO from(Summary summary) {
      return new SummaryVO(
          money(summary.paid()),
          money(summary.commissionPaid()),
          money(summary.prepayPaid()),
          count(summary.creators()),
          count(summary.orders()),
          count(summary.quantity()),
          count(summary.unverified()));
    }
  }

  /** Income detail payload. */
  public record IncomeDetailVO(
      IncomeVO income, List<CommissionLineVO> lines, List<PaymentVO> payments) {
    static IncomeDetailVO from(IncomeDetail detail) {
      return new IncomeDetailVO(
          IncomeVO.from(detail.income()),
          detail.lines().stream().map(CommissionLineVO::from).toList(),
          detail.payments().stream().map(PaymentVO::from).toList());
    }
  }

  /** One commission line (ADR-032 appendix A {@code CommissionLine}). */
  public record CommissionLineVO(
      String id,
      String orderNo,
      String itemId,
      String product,
      String sku,
      String image,
      String base,
      String rate,
      Integer rateVersion,
      String ruleVersion,
      String commission,
      String adjustment,
      String releaseBasis,
      String businessAt,
      String direction,
      String subject,
      String kolId,
      String kol) {
    static CommissionLineVO from(CommissionLine line) {
      return new CommissionLineVO(
          String.valueOf(line.id()),
          line.orderNo(),
          String.valueOf(line.itemId()),
          line.product(),
          line.sku(),
          line.image(),
          line.base(),
          line.rate(),
          line.rateVersion(),
          line.ruleVersion() == null ? null : String.valueOf(line.ruleVersion()),
          line.commission(),
          line.adjustment(),
          line.releaseBasis(),
          line.businessAt() == null ? null : line.businessAt().toString(),
          line.direction(),
          line.subject(),
          line.kolId() == null ? null : String.valueOf(line.kolId()),
          line.kol());
    }
  }

  /** One payment row (ADR-032 appendix A {@code Payment}). */
  public record PaymentVO(
      String id,
      String sourceNo,
      String type,
      String kolId,
      String amountCny,
      String paidAt,
      Boolean deduct,
      String deducted,
      String pending,
      String actualAmount,
      String actualCurrency,
      String voucher,
      String withdrawalId) {
    static PaymentVO from(PaymentLine line) {
      return new PaymentVO(
          String.valueOf(line.id()),
          line.sourceNo(),
          line.type(),
          String.valueOf(line.kolId()),
          line.amountCny(),
          line.paidAt() == null ? null : line.paidAt().toString(),
          line.deduct() != null && line.deduct().signum() > 0,
          money(line.deducted()),
          money(line.pending()),
          line.actualAmount(),
          line.actualCurrency(),
          line.voucher() == null ? null : String.valueOf(line.voucher()),
          line.withdrawalId() == null ? null : String.valueOf(line.withdrawalId()));
    }
  }

  static String money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .toPlainString();
  }

  /**
   * Counts are JSON numbers. {@code long} would be written as a string by the shared serializer.
   */
  private static int count(long value) {
    return Math.toIntExact(value);
  }
}
