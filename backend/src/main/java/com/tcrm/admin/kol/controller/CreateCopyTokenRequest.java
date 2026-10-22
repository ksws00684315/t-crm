package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.CreateCopyTokenCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateCopyTokenRequest(
    @NotNull @Min(1) Long kolId,
    @Min(1) Integer quota,
    @Min(5) Integer validMinutes,
    @Min(1) Long targetShopId,
    @NotNull @jakarta.validation.constraints.Pattern(regexp = "WEIDIAN") String targetChannel) {

  public CreateCopyTokenRequest(
      Long kolId, Integer quota, Integer validMinutes, Long targetShopId) {
    this(kolId, quota, validMinutes, targetShopId, "WEIDIAN");
  }

  CreateCopyTokenCommand toCommand() {
    return new CreateCopyTokenCommand(kolId, quota, validMinutes, targetShopId, targetChannel);
  }
}
