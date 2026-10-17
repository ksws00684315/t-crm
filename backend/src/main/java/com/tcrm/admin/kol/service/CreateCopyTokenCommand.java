package com.tcrm.admin.kol.service;

/** Create payload after bean validation; null quota / minutes take application defaults. */
public record CreateCopyTokenCommand(
    Long kolId, Integer quota, Integer validMinutes, Long targetShopId) {}
