package com.tcrm.admin.order.controller;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** One explicitly selected order line; rates are fractions, not percentages. */
public record OrderRateSupplementRequest(
    @NotNull @Positive Long orderItemId,
    @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal rate,
    @NotBlank @Size(max = 255) String reason) {}
