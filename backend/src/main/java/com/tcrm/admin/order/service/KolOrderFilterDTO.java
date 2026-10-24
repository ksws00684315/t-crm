package com.tcrm.admin.order.service;

import java.time.Instant;

/** KOL order filters; payFrom is inclusive and payTo exclusive, both UTC. */
public record KolOrderFilterDTO(
    Instant payFrom, Instant payTo, String orderStatus, int page, int size) {}
