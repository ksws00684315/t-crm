package com.tcrm.admin.product.api;

import java.math.BigDecimal;

/**
 * Sales of one creator-item mapping (task 39). Paid lines are PAID, SHIPPED, RECEIVED and
 * COMPLETED. {@code refundAmount} sums every non-deleted line of the mapping, including unpaid and
 * closed orders.
 *
 * @param kolItemId crm_kol_item.id
 * @param salesQuantity quantity of paid lines; zero when none
 * @param payAmount pay amount of paid lines
 * @param refundAmount refund amount of every line
 */
public record KolItemSalesTotal(
    Long kolItemId, long salesQuantity, BigDecimal payAmount, BigDecimal refundAmount) {}
