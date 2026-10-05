package com.tcrm.admin.product.service;

import java.math.BigDecimal;

public record EditProductCommand(
    String name, String category, String imageUrl, BigDecimal price, BigDecimal rate) {}
