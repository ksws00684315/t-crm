package com.tcrm.admin.kollibrary.service;

/**
 * List filters (requirement 1.2.5 ②): a keyword over the creator ID and the display name, platform,
 * country site, one content category, outreach status and the follow-up owner (contains).
 */
public record LibraryKolFilter(
    String keyword,
    String platform,
    String countryCode,
    String category,
    String outreachStatus,
    String followUpOwner,
    int page,
    int size) {}
