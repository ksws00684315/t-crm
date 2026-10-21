package com.tcrm.admin.product.service;

/** Creator-item list filter (task 39). Blank text is already null. */
public record KolItemFilterDTO(
    String linkCode, String kolKeyword, Long shopId, int page, int size) {}
