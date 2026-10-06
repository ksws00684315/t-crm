package com.tcrm.admin.link.controller;

import com.tcrm.admin.link.service.LinkDTO;
import java.util.List;

/**
 * One tracking link. {@code unavailableReasons}: KOL_DISABLED, PRODUCT_OFF_SHELF, SHOP_DISABLED
 * (empty when available). Click counts cover the last 30 days; valid excludes bots and duplicates.
 */
public record LinkVO(
    Long kolItemId,
    String productCode,
    String productName,
    String weidianItemId,
    String linkUrl,
    String targetUrl,
    boolean available,
    List<String> unavailableReasons,
    long clicks30d,
    long validClicks30d) {
  static LinkVO from(LinkDTO l) {
    return new LinkVO(
        l.kolItemId(),
        l.productCode(),
        l.productName(),
        l.weidianItemId(),
        l.linkUrl(),
        l.targetUrl(),
        l.available(),
        l.unavailableReasons().stream().map(Enum::name).toList(),
        l.clicks30d(),
        l.validClicks30d());
  }
}
