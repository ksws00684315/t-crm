package com.tcrm.admin.integration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WeidianAppRequest(
    @NotBlank @Size(max = 64) String appKey, @NotBlank String appSecret) {
  @Override
  public String toString() {
    return "WeidianAppRequest[appKey=%s, appSecret=redacted]".formatted(appKey);
  }
}
