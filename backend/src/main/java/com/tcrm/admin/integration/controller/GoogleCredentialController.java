package com.tcrm.admin.integration.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.integration.service.GoogleCredentialService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Company Google service account for reading product source sheets (ADR-021 §2). */
@RestController
@RequestMapping("/admin-api/integration/google-credential")
@SaCheckRole("SUPER_ADMIN")
public class GoogleCredentialController {
  private final GoogleCredentialService credentials;

  public GoogleCredentialController(GoogleCredentialService credentials) {
    this.credentials = credentials;
  }

  @GetMapping
  public R<GoogleCredentialVO> get() {
    return R.ok(GoogleCredentialVO.from(credentials.view()));
  }

  @PutMapping
  @AuditLog(module = "Google", action = "Configure service account")
  public R<GoogleCredentialVO> save(@Valid @RequestBody GoogleCredentialRequest request) {
    return R.ok(GoogleCredentialVO.from(credentials.save(request.credentialJson())));
  }
}
