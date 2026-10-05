package com.tcrm.admin.product.service;

public record ProductFilterDTO(
    String keyword, Long shopId, Integer status, String category, int page, int size) {}
