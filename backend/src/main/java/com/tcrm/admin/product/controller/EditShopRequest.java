package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.EditShopCommand;
import com.tcrm.admin.product.service.ProductRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditShopRequest(
    @NotBlank @Size(max = ProductRules.MAX_SHOP_NAME) String shopName,
    @Size(max = ProductRules.MAX_SHOP_REMARK) String remark) {
  EditShopCommand toCommand() {
    return new EditShopCommand(shopName, remark);
  }
}
