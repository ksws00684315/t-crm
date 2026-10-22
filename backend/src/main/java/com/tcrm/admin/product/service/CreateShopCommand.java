package com.tcrm.admin.product.service;

public record CreateShopCommand(
    String shopName, String channel, String weidianShopId, Integer maxItems, String remark) {}
