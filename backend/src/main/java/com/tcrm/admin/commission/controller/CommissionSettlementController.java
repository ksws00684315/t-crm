package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.CommissionSettlementService;
import com.tcrm.admin.commission.service.CommissionWithdrawalService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Management APIs for real commission withdrawals and immutable settlement rules. */
@RestController
public class CommissionSettlementController {
  private final CommissionWithdrawalService withdrawals;
  private final CommissionSettlementService settlement;
  private final CurrentUserContext current;

  public CommissionSettlementController(
      CommissionWithdrawalService withdrawals,
      CommissionSettlementService settlement,
      CurrentUserContext current) {
    this.withdrawals = withdrawals;
    this.settlement = settlement;
    this.current = current;
  }

  public record WithdrawalQuery(
      String applicationNo,
      String kol,
      String type,
      String status,
      @Min(1) Integer page,
      @Min(1) @Max(100) Integer size) {}

  public record ExportQuery(String applicationNo, String kol, String status, String type) {}

  public record ReviewRequest(@NotBlank String decision, String note) {}

  public record ConfigRequest(
      @NotBlank String version, @NotNull BigDecimal minimum, @Min(0) @Max(365) int days) {}

  public record ConfigView(
      String currency, String minimum, String version, java.util.List<ChannelView> channels) {}

  public record ChannelView(String code, String name, String basis, int days) {}

  public record RateRequest(
      @NotNull @Positive Long kolId,
      @NotNull BigDecimal rate,
      @NotNull java.time.Instant effectiveFrom,
      String evidence) {}

  @GetMapping("/admin-api/commission/withdrawals")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review"},
      mode = SaMode.OR)
  public R<PageResult<CommissionWithdrawalService.Withdrawal>> withdrawals(
      @Valid @ModelAttribute WithdrawalQuery q) {
    if (q.type() != null && !q.type().isBlank() && !"COMMISSION".equals(q.type())) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return R.ok(
        withdrawals.pageAdmin(
            current.user(),
            new CommissionWithdrawalService.Filter(
                q.applicationNo(),
                q.kol(),
                q.status(),
                q.page() == null ? 1 : q.page(),
                q.size() == null ? 20 : q.size())));
  }

  @GetMapping("/admin-api/commission/withdrawals/{id}")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review"},
      mode = SaMode.OR)
  @com.tcrm.admin.shared.AuditLog(module = "CommissionWithdrawal", action = "View detail")
  public R<CommissionWithdrawalService.Withdrawal> detail(@PathVariable @Positive Long id) {
    return R.ok(withdrawals.detailAdmin(current.user(), id));
  }

  @GetMapping("/admin-api/commission/withdrawals/{id}/orders")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review"},
      mode = SaMode.OR)
  @com.tcrm.admin.shared.AuditLog(module = "CommissionWithdrawal", action = "View allocated orders")
  public R<PageResult<CommissionWithdrawalService.WithdrawalOrder>> orders(
      @PathVariable @Positive Long id,
      @RequestParam(defaultValue = "1") @Min(1) Integer page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
    return R.ok(withdrawals.ordersAdmin(current.user(), id, page, size));
  }

  @GetMapping("/admin-api/commission/withdrawals/export")
  @SaCheckPermission(
      value = {"commission:view", "data:export"},
      mode = SaMode.AND)
  @com.tcrm.admin.shared.AuditLog(module = "CommissionWithdrawal", action = "Export list")
  public ResponseEntity<StreamingResponseBody> export(@ModelAttribute ExportQuery query) {
    if (query.type() != null && !query.type().isBlank() && !"COMMISSION".equals(query.type())) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var user = current.user();
    StreamingResponseBody body =
        output -> {
          output.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
          output.write("申请编号,达人,达人编号,申请金额(CNY),状态,申请时间\r\n".getBytes(StandardCharsets.UTF_8));
          int page = 1;
          while (true) {
            var result =
                withdrawals.pageAdmin(
                    user,
                    new CommissionWithdrawalService.Filter(
                        query.applicationNo(), query.kol(), query.status(), page, 100));
            for (var row : result.items()) {
              String line =
                  String.join(
                          ",",
                          csv(row.applicationNo()),
                          csv(row.kolName()),
                          csv(row.kolCode()),
                          csv(row.amount()),
                          csv(row.status()),
                          csv(row.createdAt().toString()))
                      + "\r\n";
              output.write(line.getBytes(StandardCharsets.UTF_8));
            }
            if (result.items().isEmpty() || (long) page * 100 >= result.total()) {
              break;
            }
            page++;
          }
        };
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("commission-withdrawals.csv").build());
    return ResponseEntity.ok().headers(headers).body(body);
  }

  @PutMapping("/admin-api/commission/withdrawals/{id}/review")
  @SaCheckPermission("withdrawal:review")
  public R<CommissionWithdrawalService.Withdrawal> review(
      @PathVariable @Positive Long id, @Valid @RequestBody ReviewRequest request) {
    return R.ok(withdrawals.review(current.user(), id, request.decision(), request.note()));
  }

  @PutMapping("/admin-api/commission/withdrawals/{id}/payment")
  @SaCheckRole("SUPER_ADMIN")
  public R<CommissionWithdrawalService.Withdrawal> payment(
      @PathVariable @Positive Long id,
      @Valid @RequestBody CommissionWithdrawalService.PaymentRequest request) {
    return R.ok(withdrawals.registerPayment(current.user(), id, request));
  }

  @GetMapping("/admin-api/commission/settlement-configs/current")
  @SaCheckPermission(
      value = {"commission:view", "commission:config"},
      mode = SaMode.OR)
  public R<ConfigView> config() {
    var user = current.user();
    var c = settlement.currentConfig(requireTenant(user));
    return R.ok(
        new ConfigView(
            c.currency(),
            c.minimum(),
            c.version(),
            java.util.List.of(new ChannelView("WEIDIAN", "weidian", "freezeBasis", c.days()))));
  }

  @PutMapping("/admin-api/commission/settlement-configs/current")
  @SaCheckRole("SUPER_ADMIN")
  public R<ConfigView> saveConfig(@Valid @RequestBody ConfigRequest request) {
    var user = current.user();
    if (user.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.AUTH_FORBIDDEN);
    }
    var c =
        settlement.saveConfig(
            requireTenant(user), user.id(), request.version(), request.minimum(), request.days());
    return R.ok(
        new ConfigView(
            c.currency(),
            c.minimum(),
            c.version(),
            java.util.List.of(new ChannelView("WEIDIAN", "weidian", "freezeBasis", c.days()))));
  }

  @PutMapping("/admin-api/commission/rates/current")
  @SaCheckRole("SUPER_ADMIN")
  public R<RateVersion> rate(@Valid @RequestBody RateRequest request) {
    var user = current.user();
    if (user.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.AUTH_FORBIDDEN);
    }
    long id =
        settlement.setRate(
            requireTenant(user),
            request.kolId(),
            request.rate(),
            request.effectiveFrom(),
            user.id(),
            request.evidence());
    return R.ok(
        new RateVersion(
            Long.toString(id),
            request.kolId().toString(),
            request.rate().setScale(4).toPlainString(),
            request.effectiveFrom()));
  }

  public record RateVersion(
      String id, String kolId, String rate, java.time.Instant effectiveFrom) {}

  private static long requireTenant(com.tcrm.admin.security.api.AccountDTO user) {
    if (user.tenantId() == null || user.tenantId() <= 0) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static String csv(String value) {
    String safe = value == null ? "" : value;
    if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }
}
