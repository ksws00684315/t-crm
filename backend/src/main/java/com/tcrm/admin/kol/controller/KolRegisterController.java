package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.controller.KolAuthController.KolLoginVO;
import com.tcrm.admin.kol.service.KolRegisterPreview;
import com.tcrm.admin.kol.service.KolSelfRegisterService;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public KOL self-registration (task 12c §4.3). These paths are excluded from the KOL login
 * interceptor; a bad link is 404 {@code KOL_REGISTER_CODE_INVALID}. A successful register returns
 * the same body as a successful {@code POST /kol-api/auth/login}.
 */
@RestController
public class KolRegisterController {
  private final KolSelfRegisterService registration;
  private final ClientIpResolver clientIps;

  public KolRegisterController(KolSelfRegisterService registration, ClientIpResolver clientIps) {
    this.registration = registration;
    this.clientIps = clientIps;
  }

  @PublicEndpoint
  @GetMapping("/kol-api/register/{code}")
  public R<KolRegisterPreviewVO> preview(@PathVariable @NotBlank String code) {
    return R.ok(KolRegisterPreviewVO.from(registration.preview(code)));
  }

  @PublicEndpoint
  @PostMapping("/kol-api/register/email-codes")
  public R<Void> emailCode(
      @Valid @RequestBody KolRegisterEmailRequest body, HttpServletRequest request) {
    registration.sendEmailCode(body.code(), body.email(), clientIps.resolve(request));
    return R.ok(null);
  }

  @PublicEndpoint
  @PostMapping("/kol-api/register")
  public R<KolLoginVO> register(
      @Valid @RequestBody KolRegisterRequest body, HttpServletRequest request) {
    return R.ok(
        KolLoginVO.from(registration.register(body.toRegistration(), clientIps.resolve(request))));
  }

  /** Only the company name. The code, the BD and the company id stay on the server. */
  public record KolRegisterPreviewVO(String tenantName) {
    static KolRegisterPreviewVO from(KolRegisterPreview preview) {
      return new KolRegisterPreviewVO(preview.tenantName());
    }
  }
}
