package com.tcrm.admin.order.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record OrderSyncRequest(
    @NotNull @Positive Long shopId, @Pattern(regexp = "ORDER|REFUND") String type) {}
