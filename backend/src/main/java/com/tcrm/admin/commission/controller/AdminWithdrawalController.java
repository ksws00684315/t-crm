package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.AccountView;
import com.tcrm.admin.commission.service.WithdrawalService.AdminRow;
import com.tcrm.admin.commission.service.WithdrawalService.Detail;
import com.tcrm.admin.commission.service.WithdrawalService.HistoryItem;
import com.tcrm.admin.commission.service.WithdrawalService.OrderView;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentCommand;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentView;
import com.tcrm.admin.commission.service.WithdrawalService.ProductView;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Management API for commission withdrawals (task 63, ADR-032 §6.2): the finance task pages, review
 * decisions, voucher upload and JSON payment registration, the audited recipient reveal,
 * order-level allocation snapshots and CSV export. Endpoints keep the OWN data scope.
 */
@RestController
public class AdminWithdrawalController {
  private final WithdrawalService withdrawals;
  private final CurrentUserContext current;

  public AdminWithdrawalController(WithdrawalService withdrawals, CurrentUserContext current) {
    this.withdrawals = withdrawals;
    this.current = current;
  }

  /** List (contract §6.2): review, pay and read-only viewers; OWN scope sees own creators. */
  @GetMapping("/admin-api/commission/withdrawals")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review", "withdrawal:pay"},
      mode = SaMode.OR)
  public R<WithdrawalPageVO> list(@Valid @ModelAttribute Query request) {
    return R.ok(
        WithdrawalPageVO.from(
            withdrawals.page(
                current.user(),
                new WithdrawalService.Filter(
                    request.applicationNo(),
                    request.kolId(),
                    request.kolKeyword(),
                    request.bdUserId(),
                    request.status(),
                    request.pageNumber(),
                    request.pageSize()))));
  }

  /**
   * CSV export (13.01): the annotation requires {@code data:export}; the service also requires a
   * withdrawal list permission, matching the contract "export AND list scope".
   */
  @GetMapping("/admin-api/commission/withdrawals/export")
  @SaCheckPermission("data:export")
  public ResponseEntity<byte[]> export(@Valid @ModelAttribute Query request) {
    byte[] content =
        withdrawals.export(
            current.user(),
            new WithdrawalService.Filter(
                request.applicationNo(),
                request.kolId(),
                request.kolKeyword(),
                request.bdUserId(),
                request.status(),
                1,
                100));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("withdrawals.csv", StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(content);
  }

  /** Detail (13.04): masked payout, finance notes for {@code withdrawal:pay}; read audited. */
  @GetMapping("/admin-api/commission/withdrawals/{id}")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review", "withdrawal:pay"},
      mode = SaMode.OR)
  @AuditLog(module = "Withdrawal", action = "View detail")
  public R<WithdrawalDetailVO> detail(@PathVariable @Min(1) long id) {
    return R.ok(WithdrawalDetailVO.from(withdrawals.detail(current.user(), id)));
  }

  /** Full payout snapshot for the payer only (contract §6.2); every read is audited. */
  @GetMapping("/admin-api/commission/withdrawals/{id}/recipient")
  @SaCheckPermission("withdrawal:pay")
  public R<WithdrawalRecipientVO> recipient(@PathVariable @Min(1) long id) {
    return R.ok(WithdrawalRecipientVO.from(withdrawals.recipient(current.user(), id)));
  }

  /** Order-level allocation snapshots (13.04): one row per order from the persisted table. */
  @GetMapping("/admin-api/commission/withdrawals/{id}/orders")
  @SaCheckPermission(
      value = {"commission:view", "withdrawal:review", "withdrawal:pay"},
      mode = SaMode.OR)
  public R<WithdrawalOrderPageVO> orders(
      @PathVariable @Min(1) long id,
      @RequestParam(defaultValue = "1") @Min(1) @Max(10000) Integer page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size) {
    return R.ok(WithdrawalOrderPageVO.from(withdrawals.orders(current.user(), id, page, size)));
  }

  /** Review decision (12.02): approve moves to PENDING_PAYMENT; reject needs a reason. */
  @PutMapping("/admin-api/commission/withdrawals/{id}/review")
  @SaCheckPermission(
      value = {"withdrawal:review", "withdrawal:pay"},
      mode = SaMode.OR)
  public R<WithdrawalDetailVO> review(
      @PathVariable @Min(1) long id, @Valid @RequestBody ReviewRequest request) {
    return R.ok(
        WithdrawalDetailVO.from(
            withdrawals.review(current.user(), id, request.decision(), request.note())));
  }

  /**
   * Voucher upload before registration (contract §6.2 POST /admin-api/files): PNG/JPEG/PDF up to
   * 10MB, referenced by {@code fileId} in the payment registration.
   */
  @PostMapping("/admin-api/files")
  @SaCheckPermission("withdrawal:pay")
  public R<FileUploadVO> uploadVoucher(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "WITHDRAWAL_VOUCHER") String bizType) {
    if (!"WITHDRAWAL_VOUCHER".equals(bizType)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    try {
      return R.ok(
          new FileUploadVO(
              withdrawals.uploadVoucher(
                  current.user(), file.getOriginalFilename(), file.getBytes())));
    } catch (IOException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  /**
   * Payment registration (13.01, contract §6.2): JSON body referencing the uploaded voucher; {@code
   * withdrawal:pay}; one successful payment per task.
   */
  @PutMapping("/admin-api/commission/withdrawals/{id}/payment")
  @SaCheckPermission("withdrawal:pay")
  public R<WithdrawalDetailVO> pay(
      @PathVariable @Min(1) long id, @Valid @RequestBody PaymentRequest request) {
    return R.ok(
        WithdrawalDetailVO.from(
            withdrawals.pay(
                current.user(),
                id,
                new PaymentCommand(
                    request.idempotencyKey(),
                    request.amountCny(),
                    request.actualAmount(),
                    request.actualCurrency(),
                    request.paidAt(),
                    request.method(),
                    request.financeNote(),
                    request.conversionNote(),
                    request.voucher()))));
  }

  /** Withdrawal occupations of order items for the order chain (09.04 提现占用). */
  @GetMapping("/admin-api/commission/withdrawal-occupations")
  @SaCheckPermission(
      value = {"order:view", "commission:view"},
      mode = SaMode.OR)
  public R<List<OccupationVO>> occupations(@RequestParam List<@Min(1) Long> orderItemIds) {
    return R.ok(
        withdrawals.occupations(current.user(), orderItemIds).stream()
            .map(OccupationVO::from)
            .toList());
  }

  public record Query(
      String applicationNo,
      @Min(1) Long kolId,
      String kolKeyword,
      @Min(1) Long bdUserId,
      String status,
      @Min(1) Integer page,
      @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  public record ReviewRequest(
      @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision, String note) {}

  /**
   * Payment registration body (contract §6.2 / appendix A {@code PaymentRegistration} + {@code
   * idempotencyKey}): the voucher is referenced as {@code voucher}, the shape the merged admin
   * frontend submits.
   */
  public record PaymentRequest(
      @NotBlank String idempotencyKey,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String amountCny,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,6})?") String actualAmount,
      @NotBlank @Pattern(regexp = "[A-Z]{3,8}") String actualCurrency,
      @NotBlank String paidAt,
      @NotBlank String method,
      String financeNote,
      String conversionNote,
      @NotNull @Min(1) Long voucher) {}

  record FileUploadVO(Long fileId) {}

  /**
   * Page of withdrawal rows. {@code total} is a JSON number (ADR-032); {@code int} keeps it out of
   * the shared long-to-string serializer.
   */
  record WithdrawalPageVO(List<WithdrawalRowVO> items, int total, int page, int size) {
    static WithdrawalPageVO from(PageResult<AdminRow> page) {
      return new WithdrawalPageVO(
          page.items().stream().map(WithdrawalRowVO::from).toList(),
          Math.toIntExact(page.total()),
          page.page(),
          page.size());
    }
  }

  /** List row (contract §6.2): same field names as the detail, masked {@code account} snapshot. */
  record WithdrawalRowVO(
      String id,
      String applicationNo,
      String kolId,
      String kolCode,
      String kolName,
      String bdId,
      String bdName,
      String type,
      String amount,
      String currency,
      String status,
      boolean paused,
      String pauseReason,
      String note,
      String reviewNote,
      String financeNote,
      String previousId,
      AccountVO account,
      String createdAt) {
    static WithdrawalRowVO from(AdminRow row) {
      return new WithdrawalRowVO(
          Long.toString(row.id()),
          row.applicationNo(),
          Long.toString(row.kolId()),
          row.kolCode(),
          row.kolName(),
          row.bdId() == null ? null : row.bdId().toString(),
          row.bdName(),
          row.type(),
          row.amountCny(),
          row.currency(),
          row.status(),
          row.paused(),
          row.pauseReason(),
          row.note(),
          row.reviewNote(),
          row.financeNote(),
          row.previousId() == null ? null : row.previousId().toString(),
          AccountVO.from(row.account()),
          row.createdAt().toString());
    }
  }

  /** Masked payout snapshot (contract §6.2 {@code AccountSnapshot}). */
  record AccountVO(
      String id,
      String qrFileId,
      String type,
      String account,
      String currency,
      String network,
      String holder) {
    static AccountVO from(AccountView view) {
      return view == null
          ? null
          : new AccountVO(
              view.accountId() == null ? null : view.accountId().toString(),
              view.qrFileId() == null ? null : view.qrFileId().toString(),
              view.type(),
              view.account(),
              view.currency(),
              view.network(),
              view.holder());
    }
  }

  /** Status history entry (contract §6.2 {@code history.{action,at,actor,note}}; no actorId). */
  record HistoryVO(String action, String at, String actor, String note, String actorRole) {
    static HistoryVO from(HistoryItem item) {
      return new HistoryVO(
          item.action(), item.at().toString(), item.actorName(), item.note(), item.actorRole());
    }
  }

  /** Payment fact (contract §6.2, {@code PaymentRegistration} shape; {@code voucher} file id). */
  record PaymentVO(
      String amountCny,
      String actualAmount,
      String actualCurrency,
      String paidAt,
      String method,
      String financeNote,
      String conversionNote,
      String voucher,
      String operatorName,
      String operatorRole) {
    static PaymentVO from(PaymentView payment) {
      return payment == null
          ? null
          : new PaymentVO(
              payment.amountCny(),
              payment.actualAmount(),
              payment.actualCurrency(),
              payment.paidAt().toString(),
              payment.method(),
              payment.financeNote(),
              payment.conversionNote(),
              payment.voucherFileId() == null ? null : payment.voucherFileId().toString(),
              payment.operatorName(),
              payment.operatorRole());
    }
  }

  /** Row-level allocation (contract §6.2 {@code Allocation}); {@code id} is the order item id. */
  record AllocationVO(
      String id,
      String orderNo,
      String image,
      String product,
      String sku,
      String occupied,
      String remainingSnapshot) {
    static AllocationVO from(WithdrawalService.AllocationView view) {
      return new AllocationVO(
          Long.toString(view.orderItemId()),
          view.orderNo(),
          view.imageUrl(),
          view.itemName(),
          view.skuName(),
          view.occupiedCny(),
          view.remainingSnapshotCny());
    }
  }

  /** Detail (contract §6.2 / appendix A {@code Withdrawal}; {@code bdId}/{@code bdName} extra). */
  record WithdrawalDetailVO(
      String id,
      String applicationNo,
      String kolId,
      String kolCode,
      String kolName,
      String bdId,
      String bdName,
      String type,
      String amount,
      String currency,
      String status,
      boolean paused,
      String pauseReason,
      String createdAt,
      String note,
      String reviewNote,
      String financeNote,
      String previousId,
      List<AllocationVO> allocations,
      AccountVO account,
      List<HistoryVO> history,
      PaymentVO payment) {
    static WithdrawalDetailVO from(Detail detail) {
      return new WithdrawalDetailVO(
          Long.toString(detail.id()),
          detail.applicationNo(),
          Long.toString(detail.kolId()),
          detail.kolCode(),
          detail.kolName(),
          detail.bdId() == null ? null : detail.bdId().toString(),
          detail.bdName(),
          detail.type(),
          detail.amountCny(),
          detail.currency(),
          detail.status(),
          detail.paused(),
          detail.pauseReason(),
          detail.createdAt().toString(),
          detail.note(),
          detail.reviewNote(),
          detail.financeNote(),
          detail.previousId() == null ? null : detail.previousId().toString(),
          detail.allocations().stream().map(AllocationVO::from).toList(),
          AccountVO.from(detail.account()),
          detail.history().stream().map(HistoryVO::from).toList(),
          PaymentVO.from(detail.payment()));
    }
  }

  /** Full payout snapshot (contract §6.2 {@code AccountSnapshot}, unmasked, payer only). */
  record WithdrawalRecipientVO(
      String id,
      String qrFileId,
      String type,
      String account,
      String currency,
      String network,
      String holder) {
    static WithdrawalRecipientVO from(WithdrawalService.Recipient recipient) {
      return new WithdrawalRecipientVO(
          recipient.accountId() == null ? null : recipient.accountId().toString(),
          recipient.qrFileId() == null ? null : recipient.qrFileId().toString(),
          recipient.type(),
          recipient.account(),
          recipient.currency(),
          recipient.network(),
          recipient.holder());
    }
  }

  /** Page of order-level snapshots; {@code total} is a JSON number (ADR-032). */
  record WithdrawalOrderPageVO(List<WithdrawalOrderVO> items, int total, int page, int size) {
    static WithdrawalOrderPageVO from(PageResult<OrderView> page) {
      return new WithdrawalOrderPageVO(
          page.items().stream().map(WithdrawalOrderVO::from).toList(),
          Math.toIntExact(page.total()),
          page.page(),
          page.size());
    }
  }

  /** One order row of the allocation pagination (contract §6.2 / #112). */
  record WithdrawalOrderVO(
      String id,
      String orderNo,
      List<ProductVO> products,
      String occupied,
      String remainingSnapshot) {
    static WithdrawalOrderVO from(OrderView order) {
      return new WithdrawalOrderVO(
          Long.toString(order.id()),
          order.orderNo(),
          order.products().stream().map(ProductVO::from).toList(),
          order.occupied(),
          order.remainingSnapshot());
    }
  }

  record ProductVO(String id, String image, String product, String sku) {
    static ProductVO from(ProductView product) {
      return new ProductVO(
          Long.toString(product.id()), product.image(), product.product(), product.sku());
    }
  }

  record OccupationVO(
      String orderItemId,
      String withdrawalId,
      String applicationNo,
      String status,
      String occupiedCny,
      String sourceReleasedCny,
      String remainingSnapshotCny,
      String submittedAt,
      String paidAt) {
    static OccupationVO from(WithdrawalService.Occupation occupation) {
      return new OccupationVO(
          Long.toString(occupation.orderItemId()),
          Long.toString(occupation.withdrawalId()),
          occupation.applicationNo(),
          occupation.status(),
          occupation.occupiedCny(),
          occupation.sourceReleasedCny(),
          occupation.remainingSnapshotCny(),
          occupation.submittedAt().toString(),
          occupation.paidAt() == null ? null : occupation.paidAt().toString());
    }
  }
}
