package com.tcrm.admin.integration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WeidianSelfUseRequest(
    @NotBlank @Size(max = 64) String appKey, @NotBlank String appSecret) {
  @Override
  public String toString() {
    return "WeidianSelfUseRequest[appKey=%s, appSecret=redacted]".formatted(appKey);
  }
}
