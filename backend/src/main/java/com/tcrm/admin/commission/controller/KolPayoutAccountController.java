package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.io.IOException;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * KOL-portal payout accounts and file uploads (task 15a §4.3). The KOL identity always comes from
 * the login state; another KOL's account or file is a 404. KOL writes go to the KOL operation audit
 * with the masked account only.
 */
@RestController
public class KolPayoutAccountController {
  private final PayoutAccountService accounts;
  private final BizFileService files;
  private final CurrentKolContext current;
  private final ClientIpResolver clientIps;

  public KolPayoutAccountController(
      PayoutAccountService accounts,
      BizFileService files,
      CurrentKolContext current,
      ClientIpResolver clientIps) {
    this.accounts = accounts;
    this.files = files;
    this.current = current;
    this.clientIps = clientIps;
  }

  /** The KOL's own accounts without the disabled ones; the owner sees full accounts. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/payout-accounts")
  public R<List<KolPayoutAccountVO>> list() {
    KolAccountDTO kol = current.kol();
    return R.ok(accounts.listMine(kol.id()).stream().map(KolPayoutAccountVO::from).toList());
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PostMapping("/kol-api/payout-accounts")
  public R<KolPayoutAccountVO> create(
      @Valid @RequestBody KolPayoutAccountRequest r, HttpServletRequest request) {
    KolAccountDTO kol = current.kol();
    return R.ok(
        KolPayoutAccountVO.from(accounts.create(kol.id(), r.toCommand(), audit(kol, request))));
  }

  /** Edits a PENDING or REJECTED account and resubmits it; APPROVED ones are locked. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/payout-accounts/{id}")
  public R<KolPayoutAccountVO> edit(
      @PathVariable @Min(1) Long id,
      @Valid @RequestBody KolPayoutAccountRequest r,
      HttpServletRequest request) {
    KolAccountDTO kol = current.kol();
    accounts.update(kol.id(), id, r.toCommand(), audit(kol, request));
    return R.ok(null);
  }

  /** Makes an APPROVED account the one default; the previous default is cleared. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/payout-accounts/{id}/default")
  public R<Void> setDefault(@PathVariable @Min(1) Long id, HttpServletRequest request) {
    KolAccountDTO kol = current.kol();
    accounts.setDefault(kol.id(), id, audit(kol, request));
    return R.ok(null);
  }

  /** Disables the account for good; the default flag drops with it. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/payout-accounts/{id}/disable")
  public R<Void> disable(@PathVariable @Min(1) Long id, HttpServletRequest request) {
    KolAccountDTO kol = current.kol();
    accounts.disable(kol.id(), id, audit(kol, request));
    return R.ok(null);
  }

  /**
   * Uploads a QR image before the account is saved; the id is referenced then (task 15a §4.2.8).
   */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PostMapping("/kol-api/files")
  public R<FileUploadVO> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "PAYOUT_QR") FileBizType bizType,
      HttpServletRequest request)
      throws IOException {
    if (bizType != FileBizType.PAYOUT_QR) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolAccountDTO kol = current.kol();
    Long fileId =
        files.uploadKolImage(kol.id(), bizType, file.getOriginalFilename(), file.getBytes());
    return R.ok(new FileUploadVO(fileId));
  }

  /** The KOL's own upload as an attachment; anyone else's file is a 404. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/files/{id}")
  public ResponseEntity<byte[]> download(@PathVariable @Min(1) Long id) {
    KolAccountDTO kol = current.kol();
    var file = files.kolFile(kol.id(), id);
    return attachment(file);
  }

  private PayoutAccountService.KolAudit audit(KolAccountDTO kol, HttpServletRequest request) {
    return new PayoutAccountService.KolAudit(
        kol.tenantId(), kol.email(), clientIps.resolve(request), request.getRequestURI());
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

  record FileUploadVO(Long fileId) {}
}
