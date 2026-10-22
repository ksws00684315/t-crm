package com.tcrm.admin.product.service;

/** Compact shop row for selector dropdowns. */
public record ShopOptionDTO(
    Long id, String shopCode, String shopName, String channel, Integer status) {}
