package com.tcrm.admin.kol.service;

/** List filters; null means no filter. {@code bdUserId} is ignored for OWN users. */
public record KolFilterDTO(
    String keyword,
    String country,
    Integer status,
    Long bdUserId,
    String reviewStatus,
    String relationStatus,
    String loginStatus,
    int page,
    int size) {}
