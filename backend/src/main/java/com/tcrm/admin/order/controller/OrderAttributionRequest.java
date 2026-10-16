package com.tcrm.admin.order.controller;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderAttributionRequest(
    @Positive Long kolId, Boolean nonKol, @NotBlank @Size(max = 255) String reason) {
  @AssertTrue
  public boolean isSingleTarget() {
    return (kolId != null) != Boolean.TRUE.equals(nonKol);
  }
}
