package com.tcrm.admin.product.service;

/** Filters for the product-task list. Blank fields are ignored. */
public record CopyJobQueryDTO(
    String jobCode, String targetChannel, String status, int page, int size) {}
