package com.tcrm.admin.kol.service;

/** The caller's active register link. {@code status} is 1; a revoked code is never returned. */
public record KolRegisterCodeDTO(String code, String url, int status) {}
