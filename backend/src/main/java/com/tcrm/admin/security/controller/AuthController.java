package com.tcrm.admin.security.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.service.AuthService;
import com.tcrm.admin.security.service.CaptchaService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and captcha issuing are public; other operations accept every administrator role. Errors
 * AUTH_* and validation 400. These paths are exempt from the {@code X-Company-Id} check.
 */
@RestController
@RequestMapping("/admin-api/auth")
public class AuthController {
  private final AuthService auth;
  private final CurrentUserContext current;
  private final ClientIpResolver clientIps;
  private final CaptchaService captchas;

  public AuthController(
      AuthService auth,
      CurrentUserContext current,
      ClientIpResolver clientIps,
      CaptchaService captchas) {
    this.auth = auth;
    this.captchas = captchas;
    this.current = current;
    this.clientIps = clientIps;
  }

  /** Issues a single-use login captcha; 429 AUTH_CAPTCHA_RATE_LIMITED past the per-IP limit. */
  @PublicEndpoint
  @PostMapping("/captchas")
  public ResponseEntity<R<CaptchaVO>> captcha(HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(R.ok(CaptchaVO.from(captchas.issue(clientIps.resolve(request)))));
  }

  @PublicEndpoint
  @PostMapping("/login")
  @AuditLog(module = "Auth", action = "Login")
  public R<LoginVO> login(@Valid @RequestBody LoginRequest r, HttpServletRequest request) {
    var result =
        auth.login(
            r.username(), r.password(), r.captchaId(), r.captchaCode(), clientIps.resolve(request));
    return R.ok(
        new LoginVO(
            result.tokenName(),
            result.token(),
            result.expiresIn(),
            UserVO.from(result.user(), result.companies())));
  }

  @SaCheckRole(
      value = {"PLATFORM_ADMIN", "SUPER_ADMIN", "STAFF"},
      mode = SaMode.OR)
  @PostMapping("/logout")
  @AuditLog(module = "Auth", action = "Logout")
  public R<Void> logout() {
    auth.logout();
    return R.ok(null);
  }

  @SaCheckRole(
      value = {"PLATFORM_ADMIN", "SUPER_ADMIN", "STAFF"},
      mode = SaMode.OR)
  @GetMapping("/me")
  public R<UserVO> me() {
    return R.ok(me(current.user()));
  }

  /**
   * Moves this token to another of the caller's companies; answers like {@code /me}. 403
   * AUTH_COMPANY_NOT_MEMBER for a company the caller cannot enter, 403 AUTH_FORBIDDEN for a
   * platform administrator, which has no company and is rejected by the role check. Audited in the
   * target company by {@code CompanySwitchAudit}.
   */
  @SaCheckRole(
      value = {"SUPER_ADMIN", "STAFF"},
      mode = SaMode.OR)
  @PostMapping("/company")
  public R<UserVO> switchCompany(@Valid @RequestBody SwitchCompanyRequest r) {
    return R.ok(me(auth.switchCompany(r.tenantId())));
  }

  private UserVO me(AccountDTO user) {
    return UserVO.from(user, auth.companies(user));
  }

  @SaCheckRole(
      value = {"PLATFORM_ADMIN", "SUPER_ADMIN", "STAFF"},
      mode = SaMode.OR)
  @PutMapping("/password")
  @AuditLog(module = "Auth", action = "Change password")
  public R<Void> password(@Valid @RequestBody PasswordRequest r) {
    auth.password(r.oldPassword(), r.newPassword());
    return R.ok(null);
  }
}
