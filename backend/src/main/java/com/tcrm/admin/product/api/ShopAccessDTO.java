package com.tcrm.admin.product.api;

/** Minimal shop identity exposed to integrations without leaking the product module's DO. */
public record ShopAccessDTO(Long id, String name, String weidianShopId, Integer status) {}
