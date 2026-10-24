package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.AccountInput;
import com.tcrm.admin.commission.service.WithdrawalService.ApplyCommand;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KOL-portal withdrawal endpoints (task 63, rows 12.01/12.02/13.02). The creator identity always
 * comes from the login state; another creator's application is a 404. Full payout snapshots are the
 * creator's own data; the admin side sees masks until allowed to reveal.
 */
@RestController
public class KolWithdrawalController {
  private final WithdrawalService withdrawals;
  private final BizFileService files;
  private final CurrentKolContext current;

  public KolWithdrawalController(
      WithdrawalService withdrawals, BizFileService files, CurrentKolContext current) {
    this.withdrawals = withdrawals;
    this.files = files;
    this.current = current;
  }

  /** Withdrawal conditions (11.03): threshold, frozen money, occupation, available balance. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/conditions")
  public R<ConditionsVO> conditions() {
    KolAccountDTO kol = current.kol();
    return R.ok(ConditionsVO.from(withdrawals.conditions(kol.tenantId(), kol.id())));
  }

  /** The creator's own applications, newest first (13.02). */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals")
  public R<PageResult<KolWithdrawalVO>> list(
      @RequestParam(defaultValue = "1") @Min(1) Integer page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size) {
    KolAccountDTO kol = current.kol();
    return R.ok(
        withdrawals.pageKol(kol.tenantId(), kol.id(), page, size).map(KolWithdrawalVO::from));
  }

  /** One application of the signed-in creator. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/{id}")
  public R<WithdrawalDetailVO> detail(@PathVariable @Min(1) long id) {
    KolAccountDTO kol = current.kol();
    return R.ok(WithdrawalDetailVO.from(withdrawals.kolDetail(kol.tenantId(), kol.id(), id)));
  }

  /** Applies with the payout info of this application only (12.01); UUID idempotent. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PostMapping("/kol-api/withdrawals")
  public R<WithdrawalDetailVO> apply(@Valid @RequestBody ApplyRequest request) {
    KolAccountDTO kol = current.kol();
    return R.ok(
        WithdrawalDetailVO.from(
            withdrawals.apply(
                kol,
                new ApplyCommand(
                    request.idempotencyKey(),
                    request.amountCny(),
                    request.previousId(),
                    request.payoutAccountId(),
                    request.account() == null
                        ? null
                        : new AccountInput(
                            request.account().type(),
                            request.account().account(),
                            request.account().holder(),
                            request.account().currency(),
                            request.account().network(),
                            request.account().qrFileId()),
                    request.note()))));
  }

  /** Withdraws before the review; the money stops being occupied (12.02). */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/withdrawals/{id}/withdraw")
  public R<WithdrawalDetailVO> withdraw(@PathVariable @Min(1) long id) {
    KolAccountDTO kol = current.kol();
    return R.ok(WithdrawalDetailVO.from(withdrawals.withdraw(kol, id)));
  }

  /** The payment voucher of the creator's own paid withdrawal. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/withdrawals/{id}/voucher")
  public ResponseEntity<byte[]> voucher(@PathVariable @Min(1) long id) {
    KolAccountDTO kol = current.kol();
    return attachment(withdrawals.kolVoucher(kol.tenantId(), kol.id(), id));
  }

  private static ResponseEntity<byte[]> attachment(BizFileService.FileDTO file) {
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

  /** Inline payout input (12.01): PayPal needs email, holder and currency; USDT address, QR. */
  public record AccountRequest(
      @NotBlank String type,
      @NotBlank String account,
      @NotBlank String holder,
      String currency,
      String network,
      @Min(1) Long qrFileId) {}

  public record ApplyRequest(
      @NotBlank String idempotencyKey,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String amountCny,
      @Min(1) Long previousId,
      @Min(1) Long payoutAccountId,
      AccountRequest account,
      String note) {}

  record ConditionsVO(
      String currency,
      String minimumWithdrawalCny,
      String frozenCny,
      String occupiedCny,
      String walletBalanceCny,
      String availableCny,
      boolean kolFrozen,
      boolean eligible) {
    static ConditionsVO from(WithdrawalService.Conditions conditions) {
      return new ConditionsVO(
          conditions.currency(),
          conditions.minimumWithdrawalCny(),
          conditions.frozenCny(),
          conditions.occupiedCny(),
          conditions.walletBalanceCny(),
          conditions.availableCny(),
          conditions.kolFrozen(),
          conditions.eligible());
    }
  }

  record AccountVO(
      String accountId,
      String type,
      String account,
      String holder,
      String currency,
      String network,
      String qrFileId) {
    static AccountVO from(WithdrawalService.AccountView view) {
      return view == null
          ? null
          : new AccountVO(
              view.accountId() == null ? null : view.accountId().toString(),
              view.type(),
              view.account(),
              view.holder(),
              view.currency(),
              view.network(),
              view.qrFileId() == null ? null : view.qrFileId().toString());
    }
  }

  record KolWithdrawalVO(
      String id,
      String applicationNo,
      String amountCny,
      String currency,
      String status,
      String createdAt,
      String rejectReason,
      String paidAt,
      String actualAmount,
      String actualCurrency,
      AccountVO account) {
    static KolWithdrawalVO from(WithdrawalService.KolRow row) {
      return new KolWithdrawalVO(
          Long.toString(row.id()),
          row.applicationNo(),
          row.amountCny(),
          row.currency(),
          row.status(),
          row.createdAt().toString(),
          row.rejectReason(),
          row.paidAt() == null ? null : row.paidAt().toString(),
          row.actualAmount(),
          row.actualCurrency(),
          AccountVO.from(row.account()));
    }
  }

  record HistoryVO(
      String action, String actorId, String actorName, String actorRole, String note, String at) {
    static HistoryVO from(WithdrawalService.HistoryItem item) {
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
    static PaymentVO from(WithdrawalService.PaymentView payment) {
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
    static WithdrawalDetailVO from(WithdrawalService.Detail detail) {
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
}
