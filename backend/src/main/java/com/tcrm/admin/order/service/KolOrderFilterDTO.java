package com.tcrm.admin.order.service;

import java.time.Instant;

/**
 * KOL order filters; payFrom is inclusive and payTo exclusive, both UTC. orderNo matches the source
 * order number and productName the creator's own item names, both case-insensitively.
 */
public record KolOrderFilterDTO(
    Instant payFrom,
    Instant payTo,
    String orderStatus,
    String orderNo,
    String productName,
    int page,
    int size) {}
