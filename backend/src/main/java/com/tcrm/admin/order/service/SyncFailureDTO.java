package com.tcrm.admin.order.service;

import java.time.Instant;

/**
 * One recent sync or import failure (task 40 §3.5, row 09.04 失败任务). Import failures carry the task
 * id; the task's error file holds the per-row detail.
 *
 * @param kind ORDER_SYNC / REFUND_SYNC / ORDER_IMPORT / REFUND_IMPORT
 */
public record SyncFailureDTO(
    Long id, Long shopId, String shopName, String kind, String error, Long taskId, Instant time) {}
