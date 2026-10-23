package com.tcrm.admin.commission.model;

import java.util.List;

/**
 * Filters for one prepay page. Null fields are omitted. {@code kolIds} is the current BD's visible
 * creators; company isolation is applied by the tenant plugin, not by this object.
 */
public record PrepayPageQuery(
    String prepayNo, Long kolId, String status, List<Long> kolIds, int limit, long offset) {}
