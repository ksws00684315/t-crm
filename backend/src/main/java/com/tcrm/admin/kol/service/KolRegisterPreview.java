package com.tcrm.admin.kol.service;

/** Company name shown on a valid register link. Nothing else about the company is exposed. */
public record KolRegisterPreview(String tenantName) {}
