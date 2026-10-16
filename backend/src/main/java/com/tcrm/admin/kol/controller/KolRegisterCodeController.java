package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.kol.service.KolRegisterCodeDTO;
import com.tcrm.admin.kol.service.KolRegisterCodeService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in BD's register link (task 12c §4.2). Permission {@code kol:register-link}; an
 * account that cannot be a BD is 422 {@code KOL_INVALID_BD_USER} even when it holds the code (a
 * super administrator does).
 */
@RestController
public class KolRegisterCodeController {
  private final KolRegisterCodeService codes;

  public KolRegisterCodeController(KolRegisterCodeService codes) {
    this.codes = codes;
  }

  /** Returns the active link, creating one the first time this BD asks. */
  @GetMapping("/admin-api/kol/register-code")
  @SaCheckPermission("kol:register-link")
  @AuditLog(module = "KOL", action = "Get register link")
  public R<KolRegisterCodeVO> current() {
    return R.ok(KolRegisterCodeVO.from(codes.current()));
  }

  /** Revokes the active link and returns a new one. */
  @PostMapping("/admin-api/kol/register-code")
  @SaCheckPermission("kol:register-link")
  @AuditLog(module = "KOL", action = "Rotate register link")
  public R<KolRegisterCodeVO> rotate() {
    return R.ok(KolRegisterCodeVO.from(codes.rotate()));
  }

  /** {@code url} is {@code {TCRM_KOL_PORTAL_BASE_URL}/register?c={code}}. {@code status} is 1. */
  public record KolRegisterCodeVO(String code, String url, int status) {
    static KolRegisterCodeVO from(KolRegisterCodeDTO dto) {
      return new KolRegisterCodeVO(dto.code(), dto.url(), dto.status());
    }
  }
}
