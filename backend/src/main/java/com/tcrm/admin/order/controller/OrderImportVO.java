package com.tcrm.admin.order.controller;

/** Accepted order import; poll {@code /task/tasks/{taskId}} for progress. */
public record OrderImportVO(Long taskId) {}
