package com.tcrm.admin.kol.api;

/**
 * Current promotion rate of a KOL for the creator-item page (task 39).
 *
 * @param kolId crm_kol.id
 * @param rateText four-decimal text such as {@code 0.1500}; null when the KOL has no rate
 */
public record KolRateDTO(Long kolId, String rateText) {}
