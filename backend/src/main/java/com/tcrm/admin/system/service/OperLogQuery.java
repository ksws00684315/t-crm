package com.tcrm.admin.system.service;

import java.time.Instant;

public record OperLogQuery(
    String module, String keyword, String operator, Instant from, Instant to, int page, int size) {}
