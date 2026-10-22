package com.tcrm.admin.product.api;

/** Shop fields other modules need without depending on the product package internals. */
public record ShopAccessDTO(
    Long id, String name, String weidianShopId, Integer status, String shopCode) {}
