package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ProductLinkRateLimiter;
import com.tcrm.admin.product.service.ProductLinkService;
import com.tcrm.admin.product.service.ProductLinkService.ConvertCommand;
import com.tcrm.admin.product.service.ProductLinkService.ConvertItem;
import com.tcrm.admin.product.service.ProductLinkService.ResolveCommand;
import com.tcrm.admin.product.service.ProductLinkService.ResolveItem;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External product-link APIs compatible with the customer Apps Script 3.0.5 (task 17b / ADR-019).
 * No admin login; authenticated by copy token where required.
 */
@RestController
@RequestMapping("/admin-api/external/product-links")
@PublicEndpoint
public class ProductLinkController {
  private final ProductLinkService links;
  private final ProductLinkRateLimiter rates;
  private final ClientIpResolver ips;

  public ProductLinkController(
      ProductLinkService links, ProductLinkRateLimiter rates, ClientIpResolver ips) {
    this.links = links;
    this.rates = rates;
    this.ips = ips;
  }

  @GetMapping("/templates")
  public R<List<ProductLinkService.LinkTemplateView>> templates(
      @RequestParam(value = "channelCode", required = false) String channelCode,
      HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(links.templates(channelCode));
  }

  @PostMapping("/convert")
  public R<ProductLinkService.ConvertResult> convert(
      @Valid @RequestBody ConvertRequest body, HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(
        links.convert(
            new ConvertCommand(
                body.token(),
                body.callerPlatform(),
                body.items().stream()
                    .map(
                        i ->
                            new ConvertItem(i.sourceChannelCode(), i.sourceProductId(), i.ruleId()))
                    .toList())));
  }

  @PostMapping("/jobs/{jobCode}/items/resolve")
  public R<ProductLinkService.ResolveResult> resolve(
      @PathVariable("jobCode") String jobCode,
      @Valid @RequestBody ResolveRequest body,
      HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(
        links.resolve(
            jobCode,
            new ResolveCommand(
                body.token(),
                body.items().stream()
                    .map(i -> new ResolveItem(i.sourceChannelCode(), i.sourceProductId()))
                    .toList())));
  }

  @GetMapping("/jobs/{jobCode}")
  public R<ProductLinkService.JobView> job(
      @PathVariable("jobCode") String jobCode, HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(links.getJob(jobCode));
  }

  @GetMapping("/jobs/{jobCode}/items")
  public R<List<ProductLinkService.JobItemView>> jobItems(
      @PathVariable("jobCode") String jobCode, HttpServletRequest request) {
    rates.checkIp(ips.resolve(request));
    return R.ok(links.listItems(jobCode));
  }

  public record ConvertRequest(
      @NotBlank @Size(max = 128) String token,
      @Size(max = 64) String callerPlatform,
      @NotEmpty @Size(max = 500) List<ConvertItemRequest> items) {}

  public record ConvertItemRequest(
      @Size(max = 16) String sourceChannelCode,
      @NotBlank @Size(max = 32) String sourceProductId,
      @Size(max = 64) String ruleId) {}

  public record ResolveRequest(
      @NotBlank @Size(max = 128) String token,
      @NotEmpty @Size(max = 50) List<ResolveItemRequest> items) {}

  public record ResolveItemRequest(
      @Size(max = 16) String sourceChannelCode, @NotBlank @Size(max = 32) String sourceProductId) {}
}
