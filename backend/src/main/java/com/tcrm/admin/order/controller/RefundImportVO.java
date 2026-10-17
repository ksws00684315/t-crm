package com.tcrm.admin.order.controller;

/** Accepted refund import; poll {@code /task/tasks/{taskId}} for progress. */
public record RefundImportVO(Long taskId) {}
