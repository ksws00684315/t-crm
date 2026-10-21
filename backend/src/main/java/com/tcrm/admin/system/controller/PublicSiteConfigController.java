package com.tcrm.admin.system.controller;

import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.shared.api.SystemConfigApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public branding and upload settings used by the browser applications. */
@RestController
public class PublicSiteConfigController {
  private final SystemConfigApi configs;

  public PublicSiteConfigController(SystemConfigApi configs) {
    this.configs = configs;
  }

  @GetMapping("/admin-api/public/site-config")
  @PublicEndpoint
  public R<AdminSiteConfig> admin() {
    return R.ok(new AdminSiteConfig(configs.getValue("site.name"), configs.getValue("url.upload")));
  }

  @GetMapping("/kol-api/public/site-config")
  @PublicEndpoint
  public R<KolSiteConfig> kol() {
    return R.ok(new KolSiteConfig(configs.getValue("site.name")));
  }

  public record AdminSiteConfig(String siteName, String uploadBaseUrl) {}

  public record KolSiteConfig(String siteName) {}
}
