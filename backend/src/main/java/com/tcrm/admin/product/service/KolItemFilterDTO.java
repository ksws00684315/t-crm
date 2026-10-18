package com.tcrm.admin.product.service;

public record KolItemFilterDTO(
    String kolCode,
    String productCode,
    Long shopId,
    String weidianItemId,
    String source,
    boolean currentOnly,
    int page,
    int size) {}
