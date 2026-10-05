package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.CreateShopCommand;
import com.tcrm.admin.product.service.ProductRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateShopRequest(
    @NotBlank @Size(max = ProductRules.MAX_SHOP_NAME) String shopName,
    @NotBlank @Pattern(regexp = ProductRules.WEIDIAN_ID_PATTERN) String weidianShopId,
    @Size(max = ProductRules.MAX_SHOP_REMARK) String remark) {
  CreateShopCommand toCommand() {
    return new CreateShopCommand(shopName, weidianShopId, remark);
  }
}
