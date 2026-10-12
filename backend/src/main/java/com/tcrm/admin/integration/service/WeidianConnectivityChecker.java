package com.tcrm.admin.integration.service;

import com.tcrm.admin.product.api.ShopAccessDTO;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WeidianConnectivityChecker {
  private final WeidianHttpGateway gateway;
  private final WeidianRateLimiter limiter;
  private final ObjectMapper json;

  public WeidianConnectivityChecker(
      WeidianHttpGateway gateway, WeidianRateLimiter limiter, ObjectMapper json) {
    this.gateway = gateway;
    this.limiter = limiter;
    this.json = json;
  }

  /** Calls the documented one-row item list and reports seller_id only as a diagnostic hint. */
  public CheckResult check(ShopAccessDTO shop, String appKey, String accessToken) {
    limiter.acquire(appKey);
    LinkedHashMap<String, Object> param = new LinkedHashMap<>();
    param.put("page_num", 1);
    param.put("page_size", 1);
    JsonNode envelope =
        gateway.apiPost("vdian.item.list.get", "1.1", accessToken, stringify(param));
    JsonNode result = WeidianResponseParser.resultOrThrow(envelope);
    JsonNode items = result.path("items");
    String sellerId = null;
    if (items.isArray() && !items.isEmpty()) {
      JsonNode seller = items.get(0).get("seller_id");
      if (seller != null && !seller.isNull()) {
        sellerId = seller.asText();
      }
    }
    boolean matches = sellerId == null || sellerId.equals(shop.weidianShopId());
    return new CheckResult(sellerId, matches);
  }

  private String stringify(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("Unable to encode Weidian check request");
    }
  }

  public record CheckResult(String sellerId, boolean sellerMatches) {}
}
