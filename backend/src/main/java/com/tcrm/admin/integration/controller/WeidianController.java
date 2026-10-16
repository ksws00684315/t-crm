package com.tcrm.admin.integration.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.integration.api.WeidianAppRequest;
import com.tcrm.admin.integration.api.WeidianAppView;
import com.tcrm.admin.integration.api.WeidianAuthorizeView;
import com.tcrm.admin.integration.api.WeidianSelfUseRequest;
import com.tcrm.admin.integration.api.WeidianShopView;
import com.tcrm.admin.integration.service.WeidianIntegrationService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin-api/integration/weidian")
public class WeidianController {
  private final WeidianIntegrationService integration;

  public WeidianController(WeidianIntegrationService integration) {
    this.integration = integration;
  }

  @GetMapping("/app")
  @SaCheckRole("SUPER_ADMIN")
  public R<WeidianAppView> app() {
    return R.ok(integration.app());
  }

  @PutMapping("/app")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Weidian", action = "Configure service app")
  public R<Void> saveApp(@Valid @RequestBody WeidianAppRequest request) {
    integration.saveApp(request);
    return R.ok(null);
  }

  @GetMapping("/shops")
  @SaCheckPermission("product:view")
  public R<List<WeidianShopView>> shops() {
    return R.ok(integration.shops());
  }

  @PutMapping("/shops/{shopId}/self-use")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Weidian", action = "Configure self-use app")
  public R<WeidianShopView> selfUse(
      @PathVariable @Min(1) Long shopId, @Valid @RequestBody WeidianSelfUseRequest request) {
    return R.ok(integration.configureSelfUse(shopId, request));
  }

  @PostMapping("/shops/{shopId}/authorizations")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Weidian", action = "Start service authorization")
  public R<WeidianAuthorizeView> authorize(@PathVariable @Min(1) Long shopId) {
    return R.ok(integration.authorizeServiceShop(shopId));
  }

  @GetMapping("/oauth/callback")
  @PublicEndpoint
  public ResponseEntity<Void> callback(
      @RequestParam(required = false) String code, @RequestParam(required = false) String state) {
    WeidianIntegrationService.CallbackResult result = integration.callback(code, state);
    String location =
        result.success()
            ? "/product/shops?weidianAuth=success"
            : "/product/shops?weidianAuth=failed&reason=" + result.reason();
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
  }

  @PostMapping("/shops/{shopId}/checks")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Weidian", action = "Check shop connection")
  public R<WeidianShopView> check(@PathVariable @Min(1) Long shopId) {
    return R.ok(integration.check(shopId));
  }
}
