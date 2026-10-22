package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.EditShopCommand;
import com.tcrm.admin.product.service.ProductRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EditShopRequest(
    @NotBlank @Size(max = ProductRules.MAX_SHOP_NAME) String shopName,
    @NotBlank @Pattern(regexp = "WEIDIAN") String channel,
    @NotBlank @Pattern(regexp = ProductRules.WEIDIAN_ID_PATTERN) String weidianShopId,
    @Min(1) @Max(1000000) Integer maxItems,
    @Size(max = ProductRules.MAX_SHOP_REMARK) String remark) {
  EditShopCommand toCommand() {
    return new EditShopCommand(shopName, channel, weidianShopId, maxItems, remark);
  }
}
