package com.tcrm.admin.commission.model;

/** Persisted detail document; recipient information is encrypted separately. */
public record WithdrawalDetailRow(
    Long id, Long tenantId, Long kolId, String detailJson, String accountCipher) {}
