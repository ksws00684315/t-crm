package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.PromotionStatus;
import jakarta.validation.constraints.NotNull;

public record PromotionStatusRequest(@NotNull PromotionStatus status) {}
