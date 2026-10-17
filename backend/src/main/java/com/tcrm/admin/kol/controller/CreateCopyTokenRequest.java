package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.CreateCopyTokenCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateCopyTokenRequest(
    @NotNull @Min(1) Long kolId,
    @Min(1) @Max(100_000) Integer quota,
    @Min(5) @Max(10_080) Integer validMinutes,
    @Min(1) Long targetShopId) {

  CreateCopyTokenCommand toCommand() {
    return new CreateCopyTokenCommand(kolId, quota, validMinutes, targetShopId);
  }
}
