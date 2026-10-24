package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retired creator promotion-list route (task 64, ADR-032 §14): the four-entry creator portal shows
 * product details only as part of the creator's own orders and finance projections.
 */
@RestController
public class KolPortalItemController {
  /** Requires a KOL token and deliberately hides the retired resource. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/items")
  public R<Void> retired() {
    throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
  }
}
