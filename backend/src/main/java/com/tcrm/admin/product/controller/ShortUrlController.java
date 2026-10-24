package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ProductLinkRateLimiter;
import com.tcrm.admin.product.service.ShortUrlResolver;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External short-URL resolution for the customer sheet script (task 57 / ADR-019). Public like
 * {@link ProductLinkController}; per-IP rate limiting is shared with the product-link APIs.
 */
@RestController
@RequestMapping("/admin-api/external/short-urls")
@PublicEndpoint
public class ShortUrlController {
  private final ShortUrlResolver resolver;
  private final ProductLinkRateLimiter rates;
  private final ClientIpResolver ips;

  public ShortUrlController(
      ShortUrlResolver resolver, ProductLinkRateLimiter rates, ClientIpResolver ips) {
    this.resolver = resolver;
    this.rates = rates;
    this.ips = ips;
  }

  @GetMapping("/resolve")
  public R<ResolveResponse> resolve(
      @RequestParam("url") @NotBlank String url, HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(new ResolveResponse(resolver.resolve(url)));
  }

  public record ResolveResponse(String url) {}
}
