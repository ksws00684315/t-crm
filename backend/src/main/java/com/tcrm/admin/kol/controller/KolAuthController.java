package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.kol.service.KolAuthService;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * KOL-portal authentication (task 12a §4.3). Public endpoints are excluded from the /kol-api/**
 * login interceptor in {@code SecurityConfiguration}; everything else requires a KOL token. Error
 * codes as agreed with task 12b: AUTH_INVALID_CREDENTIALS 401, AUTH_ACCOUNT_LOCKED 429,
 * AUTH_ACCOUNT_DISABLED 401, AUTH_UNAUTHORIZED 401, KOL_AUTH_TOKEN_INVALID 400,
 * AUTH_OLD_PASSWORD_INCORRECT 422, KOL_LOGIN_SELECTION_INVALID 400.
 */
@RestController
public class KolAuthController {
  private final KolAuthService auth;
  private final ClientIpResolver clientIps;

  public KolAuthController(KolAuthService auth, ClientIpResolver clientIps) {
    this.auth = auth;
    this.clientIps = clientIps;
  }

  /** Single match returns the login; several enabled matches return the company selection. */
  @PublicEndpoint
  @PostMapping("/kol-api/auth/login")
  public R<?> login(@Valid @RequestBody KolLoginRequest r, HttpServletRequest request) {
    Object result = auth.login(r.email(), r.password(), clientIps.resolve(request));
    if (result instanceof KolAuthService.LoginResult login) {
      return R.ok(KolLoginVO.from(login));
    }
    return R.ok(KolLoginSelectionVO.from((KolAuthService.SelectionResult) result));
  }

  @PublicEndpoint
  @PostMapping("/kol-api/auth/login/select")
  public R<KolLoginVO> select(
      @Valid @RequestBody KolSelectCompanyRequest r, HttpServletRequest request) {
    return R.ok(KolLoginVO.from(auth.select(r.selectionToken(), r.tenantId())));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PostMapping("/kol-api/auth/logout")
  public R<Void> logout() {
    auth.logout();
    return R.ok(null);
  }

  /** Invitation activation sets the password and signs the KOL in at once. */
  @PublicEndpoint
  @PostMapping("/kol-api/auth/activate")
  public R<KolLoginVO> activate(
      @Valid @RequestBody KolTokenPasswordRequest r, HttpServletRequest request) {
    return R.ok(
        KolLoginVO.from(auth.activate(r.token(), r.password(), clientIps.resolve(request))));
  }

  /** Always succeeds; a mail is only sent when the email has activated accounts (§4.4). */
  @PublicEndpoint
  @PostMapping("/kol-api/auth/forgot")
  public R<Void> forgot(@Valid @RequestBody KolForgotRequest r, HttpServletRequest request) {
    auth.forgot(r.email(), clientIps.resolve(request));
    return R.ok(null);
  }

  @PublicEndpoint
  @PostMapping("/kol-api/auth/reset")
  public R<Void> reset(@Valid @RequestBody KolTokenPasswordRequest r, HttpServletRequest request) {
    auth.reset(r.token(), r.password(), clientIps.resolve(request));
    return R.ok(null);
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/me")
  public R<KolMeVO> me() {
    return R.ok(KolMeVO.from(auth.me()));
  }

  /** Returns a fresh login so the current session survives its own password change. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @PutMapping("/kol-api/me/password")
  public R<KolLoginVO> password(
      @Valid @RequestBody KolPasswordChangeRequest r, HttpServletRequest request) {
    return R.ok(
        KolLoginVO.from(
            auth.changePassword(r.oldPassword(), r.newPassword(), clientIps.resolve(request))));
  }

  /** Login answer shared by login, select, activate and password change (§4.8). */
  public record KolLoginVO(String tokenName, String token, long expiresIn, KolMeVO kol) {
    static KolLoginVO from(KolAuthService.LoginResult result) {
      return new KolLoginVO(
          result.tokenName(), result.token(), result.expiresIn(), KolMeVO.from(result.kol()));
    }
  }

  /** Company choice for a multi-company login (§4.8). */
  public record KolCompanyVO(String tenantId, String tenantName) {}

  /** {@code kol} mirrors GET /kol-api/me (§4.8); task 11 later adds whatsapp and sheetUrl. */
  public record KolMeVO(
      String kolCode, String name, String email, String country, String tenantName) {
    static KolMeVO from(KolAccountDTO kol) {
      return new KolMeVO(kol.kolCode(), kol.name(), kol.email(), kol.country(), kol.tenantName());
    }
  }

  /** Selection credential answer when several enabled companies matched (§4.8). */
  public record KolLoginSelectionVO(String selectionToken, java.util.List<KolCompanyVO> companies) {
    static KolLoginSelectionVO from(KolAuthService.SelectionResult result) {
      return new KolLoginSelectionVO(
          result.selectionToken(),
          result.companies().stream()
              .map(c -> new KolCompanyVO(String.valueOf(c.tenantId()), c.tenantName()))
              .toList());
    }
  }
}
