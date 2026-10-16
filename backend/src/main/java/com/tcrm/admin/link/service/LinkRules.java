package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.link.model.LinkUnavailableReason;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Link status and Redis index value rules (task 06 §4.3; design §7.4). The target URL follows the
 * single rule in {@link LinkUrls#targetUrl(KolRefDTO, String)} (task 11 §4.4): agent template with
 * invite code when possible, Weidian page otherwise.
 */
final class LinkRules {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private LinkRules() {}

  /** Reasons the link is unusable; empty when the KOL is enabled, product on sale, shop enabled. */
  static List<LinkUnavailableReason> reasons(boolean kolEnabled, KolItemLinkDTO link) {
    List<LinkUnavailableReason> reasons = new ArrayList<>(3);
    if (!kolEnabled) {
      reasons.add(LinkUnavailableReason.KOL_DISABLED);
    }
    if (!link.productOnSale()) {
      reasons.add(LinkUnavailableReason.PRODUCT_OFF_SHELF);
    }
    if (!link.shopEnabled()) {
      reasons.add(LinkUnavailableReason.SHOP_DISABLED);
    }
    return reasons;
  }

  /** 1 when usable, 0 otherwise; the redirect service treats 0 as a miss (fallback page). */
  static int status(boolean kolEnabled, KolItemLinkDTO link) {
    return kolEnabled && link.productOnSale() && link.shopEnabled() ? 1 : 0;
  }

  /**
   * JSON value of the field {@code productCode} in {@code HASH link:{kolCode}} — and of the field
   * {@code weidianItemId} in {@code HASH linkitem:{nn}}, where it is byte-identical: {@code
   * {"kolItemId":"<id>","targetUrl":"<url>","status":1|0}}, the format the redirect service parses.
   */
  static String indexValue(KolRefDTO kol, KolItemLinkDTO link) {
    return JSON.writeValueAsString(
        new IndexValue(
            link.kolItemId().toString(),
            LinkUrls.targetUrl(kol, link.weidianItemId()),
            status(kol.enabled(), link)));
  }

  /** Bucket suffix of a linkitem hash: the last two digits of the item ID, zero-padded. */
  static String itemBucket(String weidianItemId) {
    int length = weidianItemId.length();
    return length >= 2
        ? weidianItemId.substring(length - 2)
        : "0".repeat(2 - length) + weidianItemId;
  }

  /** Serialized form of one index field; kolItemId is a string so 64-bit IDs stay exact. */
  private record IndexValue(String kolItemId, String targetUrl, int status) {}
}
