package com.tcrm.admin.link.service;

import com.tcrm.admin.link.model.LinkUnavailableReason;
import java.util.List;

/** One tracking link of a KOL with its click counts over the last 30 days. */
public record LinkDTO(
    Long kolItemId,
    String productCode,
    String productName,
    String weidianItemId,
    String linkUrl,
    String targetUrl,
    boolean available,
    List<LinkUnavailableReason> unavailableReasons,
    long clicks30d,
    long validClicks30d) {}
