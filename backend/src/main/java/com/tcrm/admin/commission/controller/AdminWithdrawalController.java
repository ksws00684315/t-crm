package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.AccountView;
import com.tcrm.admin.commission.service.WithdrawalService.Detail;
import com.tcrm.admin.commission.service.WithdrawalService.HistoryItem;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentCommand;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentView;
import com.tcrm.admin.commission.service.WithdrawalService.UploadedFile;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Management API for commission withdrawals (task 63, rows 13.01-13.04): the finance task pages,
 * review decisions, payment registration with voucher, the recipient reveal endpoint (audited) and
 * CSV export. All endpoints AND their service keep the OWN data scope.
 */
@RestController
public class AdminWithdrawalController {
  private final WithdrawalService withdrawals;
  private final CurrentUserContext current;

  public AdminWithdrawalController(WithdrawalService withdrawals, CurrentUserContext current) {
    this.withdrawals = withdrawals;
    this.current = current;
  }

  /** List (13.02): reviewers and read-only viewers; OWN scope sees only own creators. */
  @GetMapping("/admin-api/commission/withdrawals")
  @SaCheckPermission(
      value = {"withdrawal:review", "commission:view"},
      mode = SaMode.OR)
  public R<PageResult<WithdrawalRowVO>> list(@Valid @ModelAttribute Query request) {
    return R.ok(
        withdrawals
            .page(
                current.user(),
                new WithdrawalService.Filter(
                    request.applicationNo(),
                    request.kolCode(),
                    request.status(),
                    request.pageNumber(),
                    request.pageSize()))
            .map(WithdrawalRowVO::from));
  }

  /**
   * CSV export (13.01): the annotation requires {@code data:export}; the service also requires a
   * withdrawal list permission, matching the design "export AND list scope".
   */
  @GetMapping("/admin-api/commission/withdrawals/export")
  @SaCheckPermission("data:export")
  public ResponseEntity<byte[]> export(@Valid @ModelAttribute Query request) {
    byte[] content =
        withdrawals.export(
            current.user(),
            new WithdrawalService.Filter(
                request.applicationNo(), request.kolCode(), request.status(), 1, 100));
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

  /** Detail (13.04): masked payout; finance notes only for finance viewers. */
  @GetMapping("/admin-api/commission/withdrawals/{id}")
  @SaCheckPermission(
      value = {"withdrawal:review", "commission:view"},
      mode = SaMode.OR)
  @AuditLog(module = "Withdrawal", action = "View detail")
  public R<WithdrawalDetailVO> detail(@PathVariable @Min(1) long id) {
    return R.ok(WithdrawalDetailVO.from(withdrawals.detail(current.user(), id)));
  }

  /** Full payout snapshot for reviewers and payers; every read is audited (13.04). */
  @GetMapping("/admin-api/commission/withdrawals/{id}/recipient")
  @SaCheckPermission(
      value = {"withdrawal:review", "prepay:pay"},
      mode = SaMode.OR)
  public R<WithdrawalRecipientVO> recipient(@PathVariable @Min(1) long id) {
    return R.ok(WithdrawalRecipientVO.from(withdrawals.recipient(current.user(), id)));
  }

  /** Order items occupied by one application (13.04), paged, immutable snapshots. */
  @GetMapping("/admin-api/commission/withdrawals/{id}/orders")
  @SaCheckPermission(
      value = {"withdrawal:review", "commission:view"},
      mode = SaMode.OR)
  public R<PageResult<WithdrawalAllocationVO>> orders(
      @PathVariable @Min(1) long id,
      @RequestParam(defaultValue = "1") @Min(1) @Max(10000) Integer page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size) {
    return R.ok(
        withdrawals.orders(current.user(), id, page, size).map(WithdrawalAllocationVO::from));
  }

  /** The payment voucher of one withdrawal (13.04 关联支付凭证). */
  @GetMapping("/admin-api/commission/withdrawals/{id}/voucher")
  @SaCheckPermission(
      value = {"withdrawal:review", "prepay:pay", "commission:view"},
      mode = SaMode.OR)
  public ResponseEntity<byte[]> voucher(@PathVariable @Min(1) long id) {
    BizFileService.FileDTO file = withdrawals.voucher(current.user(), id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .body(file.content());
  }

  /** Review decision (12.02): approve moves to PENDING_PAYMENT; reject needs a reason. */
  @PutMapping("/admin-api/commission/withdrawals/{id}/review")
  @SaCheckPermission(
      value = {"withdrawal:review", "prepay:pay"},
      mode = SaMode.OR)
  public R<WithdrawalDetailVO> review(
      @PathVariable @Min(1) long id, @Valid @RequestBody ReviewRequest request) {
    return R.ok(
        WithdrawalDetailVO.from(
            withdrawals.review(current.user(), id, request.decision(), request.note())));
  }

  /** Payment registration (13.01): one successful payment per task, voucher required. */
  @PutMapping(
      value = "/admin-api/commission/withdrawals/{id}/payment",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @SaCheckPermission("prepay:pay")
  public R<WithdrawalDetailVO> pay(
      @PathVariable @Min(1) long id,
      @Valid @RequestPart("payload") PaymentRequest request,
      @RequestPart("voucher") MultipartFile voucher) {
    return R.ok(
        WithdrawalDetailVO.from(
            withdrawals.pay(
                current.user(),
                id,
                new PaymentCommand(
                    request.requestId(),
                    request.amountCny(),
                    request.actualAmount(),
                    request.actualCurrency(),
                    request.paidAt(),
                    request.method(),
                    request.financeNote(),
                    request.conversionNote()),
                uploaded(voucher))));
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

  private static UploadedFile uploaded(MultipartFile file) {
    try {
      return new UploadedFile(file.getOriginalFilename(), file.getBytes());
    } catch (IOException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  public record Query(
      String applicationNo,
      String kolCode,
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

  public record PaymentRequest(
      @NotBlank String requestId,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String amountCny,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String actualAmount,
      @NotBlank @Pattern(regexp = "[A-Z]{3,8}") String actualCurrency,
      @NotBlank String paidAt,
      @NotBlank String method,
      String financeNote,
      String conversionNote) {}

  record WithdrawalRowVO(
      String id,
      String applicationNo,
      String kolId,
      String kolCode,
      String kolName,
      String bdId,
      String bdName,
      String type,
      String amountCny,
      String currency,
      String status,
      String createdAt,
      String accountType,
      String accountMasked) {
    static WithdrawalRowVO from(WithdrawalService.AdminRow row) {
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
          row.createdAt().toString(),
          row.accountType(),
          row.accountMasked());
    }
  }

  record AccountVO(String type, String account, String holder, String currency, String network) {
    static AccountVO from(AccountView view) {
      return view == null
          ? null
          : new AccountVO(
              view.type(), view.account(), view.holder(), view.currency(), view.network());
    }
  }

  record HistoryVO(
      String action, String actorId, String actorName, String actorRole, String note, String at) {
    static HistoryVO from(HistoryItem item) {
      return new HistoryVO(
          item.action(),
          item.actorId() == null ? null : item.actorId().toString(),
          item.actorName(),
          item.actorRole(),
          item.note(),
          item.at().toString());
    }
  }

  record PaymentVO(
      String amountCny,
      String actualAmount,
      String actualCurrency,
      String paidAt,
      String method,
      String financeNote,
      String conversionNote,
      String voucherFileId,
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

  record WithdrawalDetailVO(
      String id,
      String applicationNo,
      String kolId,
      String kolCode,
      String kolName,
      String bdId,
      String bdName,
      String type,
      String amountCny,
      String currency,
      String status,
      String createdAt,
      String note,
      String reviewNote,
      String financeNote,
      String previousId,
      String allocationCount,
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
          detail.createdAt().toString(),
          detail.note(),
          detail.reviewNote(),
          detail.financeNote(),
          detail.previousId() == null ? null : detail.previousId().toString(),
          Long.toString(detail.allocationCount()),
          AccountVO.from(detail.account()),
          detail.history().stream().map(HistoryVO::from).toList(),
          PaymentVO.from(detail.payment()));
    }
  }

  record WithdrawalRecipientVO(
      String type, String account, String holder, String currency, String network) {
    static WithdrawalRecipientVO from(WithdrawalService.Recipient recipient) {
      return new WithdrawalRecipientVO(
          recipient.type(),
          recipient.account(),
          recipient.holder(),
          recipient.currency(),
          recipient.network());
    }
  }

  record WithdrawalAllocationVO(
      String id,
      String orderItemId,
      String orderNo,
      String itemName,
      String skuName,
      String imageUrl,
      String occupiedCny,
      String remainingSnapshotCny,
      String sourceReleasedCny) {
    static WithdrawalAllocationVO from(WithdrawalRows.AllocationRow row) {
      return new WithdrawalAllocationVO(
          Long.toString(row.id()),
          Long.toString(row.orderItemId()),
          row.orderNo(),
          row.itemName(),
          row.skuName(),
          row.imageUrl(),
          row.occupiedCny().setScale(2).toPlainString(),
          row.remainingSnapshotCny().setScale(2).toPlainString(),
          row.sourceReleasedCny().setScale(2).toPlainString());
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
