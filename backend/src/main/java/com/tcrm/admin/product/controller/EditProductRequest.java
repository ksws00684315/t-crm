package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.EditProductCommand;
import com.tcrm.admin.product.service.ProductRules;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record EditProductRequest(
    @NotBlank @Size(max = ProductRules.MAX_NAME) String name,
    @Size(max = ProductRules.MAX_CATEGORY) String category,
    @Size(max = ProductRules.MAX_IMAGE_URL)
        @Pattern(regexp = "^$|https://[^\\s/?#]+\\S*")
        String imageUrl,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal price,
    @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal rate) {
  EditProductCommand toCommand() {
    return new EditProductCommand(name, category, imageUrl, price, rate);
  }
}
