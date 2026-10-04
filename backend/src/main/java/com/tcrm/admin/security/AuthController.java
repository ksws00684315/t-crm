package com.tcrm.admin.security;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.common.*;
import com.tcrm.admin.system.UserInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin-api/auth")
public class AuthController {
  private final AuthService auth;
  private final CurrentUserContext current;

  public AuthController(AuthService auth, CurrentUserContext current) {
    this.auth = auth;
    this.current = current;
  }

  public record LoginRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @NotBlank @Size(max = 72) String password) {}

  public record PasswordRequest(
      @NotBlank @Size(max = 72) String oldPassword,
      @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String newPassword) {}

  @PostMapping("/login")
  @AuditLog(module = "Auth", action = "Login")
  public R<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
    return R.ok(auth.login(request.username(), request.password()));
  }

  @PostMapping("/logout")
  @AuditLog(module = "Auth", action = "Logout")
  public R<Void> logout() {
    StpUtil.logout();
    return R.ok(null);
  }

  @GetMapping("/me")
  public R<UserInfo> me() {
    return R.ok(UserInfo.from(current.user()));
  }

  @PutMapping("/password")
  @AuditLog(module = "Auth", action = "Change password")
  public R<Void> password(@Valid @RequestBody PasswordRequest request) {
    auth.password(request.oldPassword(), request.newPassword());
    return R.ok(null);
  }
}
