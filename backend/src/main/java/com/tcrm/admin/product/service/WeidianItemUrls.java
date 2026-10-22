package com.tcrm.admin.product.service;

/** Weidian item page. The only channel in this system is Weidian (task 39). */
final class WeidianItemUrls {
  private WeidianItemUrls() {}

  static String of(String itemId) {
    if (itemId == null || itemId.isBlank()) {
      return null;
    }
    return "https://weidian.com/item.html?itemID=" + itemId;
  }
}
