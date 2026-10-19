package com.tcrm.admin.kol.service;

/** BD display name and company name shown on a valid register link (task 29). */
public record KolRegisterPreview(String bdUserName, String tenantName) {}
