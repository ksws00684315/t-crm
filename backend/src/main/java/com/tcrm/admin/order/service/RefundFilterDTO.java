package com.tcrm.admin.order.service;

/** Validated refund list filters (task 40 §3.3). IDs never select another company. */
public record RefundFilterDTO(
    String refundNo,
    String orderNo,
    String channel,
    String refundStatus,
    String refundType,
    int page,
    int size) {}
