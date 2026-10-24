package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Management API for creator prepay applications and finance task completion. */
@RestController
public class PrepayController {
  private final PrepayService prepays;
  private final CurrentUserContext current;

  public PrepayController(PrepayService prepays, CurrentUserContext current) {
    this.prepays = prepays;
    this.current = current;
  }

  @GetMapping("/admin-api/commission/prepays")
  @SaCheckPermission(
      value = {
        "prepay:apply",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view"
      },
      mode = SaMode.OR)
  public R<PageResult<PrepayRowVO>> list(@Valid @ModelAttribute Query request) {
    PageResult<PrepayService.PrepayRow> page =
        prepays.page(
            current.user(),
            new PrepayService.Filter(
                request.prepayNo(),
                request.kolCode(),
                request.status(),
                request.pageNumber(),
                request.pageSize()));
    return R.ok(page == null ? null : page.map(PrepayRowVO::from));
  }

  @GetMapping("/admin-api/commission/prepays/active")
  @SaCheckPermission("prepay:apply")
  public R<PrepayDetailVO> active(@RequestParam @Positive Long kolId) {
    return R.ok(PrepayDetailVO.from(prepays.activeApplication(current.user(), kolId)));
  }

  @GetMapping("/admin-api/commission/prepays/kol-summaries")
  @SaCheckPermission("kol:view")
  public R<List<PrepayKolSummaryVO>> summaries(@RequestParam List<@Positive Long> kolId) {
    List<PrepayService.KolSummary> rows = prepays.summaries(current.user(), kolId);
    return R.ok(rows == null ? List.of() : rows.stream().map(PrepayKolSummaryVO::from).toList());
  }

  /**
   * CSV export. Annotation requires {@code data:export}; service also requires a prepay list
   * permission ({@code prepay:apply}/{@code prepay:review}/{@code prepay:finance-review}/{@code
   * prepay:pay}/{@code commission:view}), matching the design "export AND list scope".
   */
  @GetMapping("/admin-api/commission/prepays/export")
  @SaCheckPermission("data:export")
  public ResponseEntity<byte[]> export(@Valid @ModelAttribute Query request) {
    byte[] content =
        prepays.export(
            current.user(),
            new PrepayService.Filter(
                request.prepayNo(), request.kolCode(), request.status(), 1, 100));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("prepay-tasks.csv", StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(content);
  }

  @PostMapping(
      value = "/admin-api/commission/prepays",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @SaCheckPermission("prepay:apply")
  public R<PrepayDetailVO> apply(@Valid @RequestPart("payload") ApplyRequest request) {
    return R.ok(
        PrepayDetailVO.from(
            prepays.apply(
                current.user(),
                new PrepayService.PrepayApplication(
                    request.requestId(), request.kolId(), request.amountCny()))));
  }

  @PutMapping(
      value = "/admin-api/commission/prepays/{id}/application-details",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @SaCheckPermission("prepay:apply")
  public R<PrepayDetailVO> saveApplicationDetails(
      @PathVariable @Positive Long id,
      @Valid @RequestPart("payload") DetailsRequest request,
      @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments) {
    return R.ok(
        PrepayDetailVO.from(
            prepays.saveApplicationDetails(
                current.user(),
                id,
                new PrepayService.ApplicationDetails(
                    request.reason(),
                    request.expectedPayDate(),
                    request.payoutAccountId(),
                    request.deductFromCommission(),
                    request.recipientText()),
                attachments == null
                    ? List.of()
                    : attachments.stream().map(PrepayController::uploaded).toList())));
  }

  @GetMapping("/admin-api/commission/prepays/payout-accounts")
  @SaCheckPermission(
      value = {"prepay:apply", "prepay:review", "prepay:finance-review", "prepay:pay"},
      mode = SaMode.OR)
  public R<List<PrepayPayoutOptionVO>> payoutAccounts(@RequestParam @Positive Long kolId) {
    List<PrepayService.PayoutOption> rows = prepays.payoutOptions(current.user(), kolId);
    return R.ok(rows == null ? List.of() : rows.stream().map(PrepayPayoutOptionVO::from).toList());
  }

  @GetMapping("/admin-api/commission/prepays/{id}")
  @SaCheckPermission(
      value = {
        "prepay:apply",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view"
      },
      mode = SaMode.OR)
  public R<PrepayDetailVO> detail(@PathVariable @Positive Long id) {
    return R.ok(PrepayDetailVO.from(prepays.detail(current.user(), id)));
  }

  @PutMapping("/admin-api/commission/prepays/{id}/approval")
  @SaCheckPermission(
      value = {"prepay:review", "prepay:finance-review"},
      mode = SaMode.OR)
  public R<PrepayDetailVO> approval(
      @PathVariable @Positive Long id, @Valid @RequestBody ApprovalRequest request) {
    return R.ok(
        PrepayDetailVO.from(
            prepays.approval(current.user(), id, request.decision(), request.reason())));
  }

  @PutMapping("/admin-api/commission/prepays/{id}/cancel")
  @SaCheckPermission("prepay:apply")
  public R<PrepayDetailVO> cancel(
      @PathVariable @Positive Long id, @RequestBody(required = false) CancelRequest request) {
    return R.ok(
        PrepayDetailVO.from(
            prepays.cancel(current.user(), id, request == null ? null : request.reason())));
  }

  @GetMapping("/admin-api/commission/prepays/{id}/recipient")
  @SaCheckPermission(
      value = {
        "prepay:apply",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view"
      },
      mode = SaMode.OR)
  public R<PrepayRecipientVO> recipient(@PathVariable @Positive Long id) {
    return R.ok(PrepayRecipientVO.from(prepays.recipient(current.user(), id)));
  }

  @PutMapping(
      value = "/admin-api/commission/prepays/{id}/paid",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @SaCheckPermission("prepay:pay")
  public R<PrepayDetailVO> pay(
      @PathVariable @Positive Long id,
      @Valid @RequestPart("payload") PaymentRequest request,
      @RequestPart("voucher") MultipartFile voucher) {
    return R.ok(
        PrepayDetailVO.from(
            prepays.pay(
                current.user(),
                id,
                new PrepayService.PaymentCommand(
                    request.requestId(),
                    request.payAmountCny(),
                    request.payTime(),
                    request.payMethod(),
                    request.payRemark()),
                uploaded(voucher))));
  }

  private static PrepayService.UploadedFile uploaded(MultipartFile file) {
    try {
      return new PrepayService.UploadedFile(file.getOriginalFilename(), file.getBytes());
    } catch (IOException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  public record ApplyRequest(
      @NotBlank String requestId,
      @NotNull @Positive Long kolId,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String amountCny) {}

  public record DetailsRequest(
      @NotBlank String reason,
      @NotNull LocalDate expectedPayDate,
      @Positive Long payoutAccountId,
      @NotNull Boolean deductFromCommission,
      @jakarta.validation.constraints.Size(max = 1000) String recipientText) {}

  public record Query(
      String prepayNo,
      String kolCode,
      String status,
      @Min(1) Integer page,
      @Min(1) @jakarta.validation.constraints.Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  public record ApprovalRequest(
      @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision, String reason) {}

  public record CancelRequest(String reason) {}

  public record PaymentRequest(
      @NotBlank String requestId,
      @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?") String payAmountCny,
      @NotBlank String payTime,
      @NotBlank String payMethod,
      String payRemark) {}
}
