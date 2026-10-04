package com.tcrm.admin.security.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.service.AuthService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login is public; other operations accept all administrator roles. Errors AUTH_* and validation
 * 400.
 */
@RestController
@RequestMapping("/admin-api/auth")
public class AuthController {
  private final AuthService auth;
  private final CurrentUserContext current;

  public AuthController(AuthService auth, CurrentUserContext current) {
    this.auth = auth;
    this.current = current;
  }

  @PublicEndpoint
  @PostMapping("/login")
  @AuditLog(module = "Auth", action = "Login")
  public R<LoginVO> login(@Valid @RequestBody LoginRequest r) {
    var result = auth.login(r.username(), r.password());
    return R.ok(
        new LoginVO(
            result.tokenName(), result.token(), result.expiresIn(), UserVO.from(result.user())));
  }

  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
      mode = SaMode.OR)
  @PostMapping("/logout")
  @AuditLog(module = "Auth", action = "Logout")
  public R<Void> logout() {
    auth.logout();
    return R.ok(null);
  }

  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
      mode = SaMode.OR)
  @GetMapping("/me")
  public R<UserVO> me() {
    return R.ok(UserVO.from(current.user()));
  }

  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
      mode = SaMode.OR)
  @PutMapping("/password")
  @AuditLog(module = "Auth", action = "Change password")
  public R<Void> password(@Valid @RequestBody PasswordRequest r) {
    auth.password(r.oldPassword(), r.newPassword());
    return R.ok(null);
  }
}
