package com.tcrm.admin.product.service;

public record KolItemFilterDTO(
    String kolCode,
    String productCode,
    Long shopId,
    String weidianItemId,
    boolean currentOnly,
    int page,
    int size) {}
