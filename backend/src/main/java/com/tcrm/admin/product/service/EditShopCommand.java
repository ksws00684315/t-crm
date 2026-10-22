package com.tcrm.admin.product.service;

public record EditShopCommand(
    String shopName, String channel, String weidianShopId, Integer maxItems, String remark) {}
