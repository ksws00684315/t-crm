package com.tcrm.admin.link.service;

/** Link list filters: KOL code (required), exact original product code (optional), paging. */
public record LinkFilterDTO(String kolCode, String productCode, int page, int size) {}
