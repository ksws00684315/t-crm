package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ShopOptionDTO;

/** Compact shop row for selector dropdowns (task 37 options contract). */
public record ShopOptionVO(
    Long id, String shopCode, String shopName, String channel, Integer status) {
  static ShopOptionVO from(ShopOptionDTO s) {
    return new ShopOptionVO(s.id(), s.shopCode(), s.shopName(), s.channel(), s.status());
  }
}
