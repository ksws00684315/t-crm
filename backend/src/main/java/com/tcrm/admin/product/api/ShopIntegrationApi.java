package com.tcrm.admin.product.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Authorization and lightweight Weidian probes required by shop management. */
public interface ShopIntegrationApi {
  /** Returns authorization status for shops visible in the current company. */
  Map<Long, ShopAuthorizationDTO> statuses(Collection<Long> shopIds);

  /** Checks product, order, and refund APIs independently and returns sanitized results. */
  ShopApiChecksDTO checkApis(long shopId);

  /** Counts the shop's on-sale items using the Weidian item-list API. */
  int countOnSaleItems(long shopId);

  record ShopAuthorizationDTO(String status, Instant expiresAt) {}

  record ShopApiCheckDTO(Boolean ok, Instant checkedAt, String error) {}

  record ShopApiChecksDTO(ShopApiCheckDTO product, ShopApiCheckDTO order, ShopApiCheckDTO refund) {}
}
