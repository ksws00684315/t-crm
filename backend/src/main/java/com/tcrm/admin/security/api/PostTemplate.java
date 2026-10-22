package com.tcrm.admin.security.api;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Preset checked when a staff account is created. It only pre-fills permissions and the default
 * data scope; it is not a role, and changing it later does not rewrite existing grants.
 */
public enum PostTemplate {
  OPERATOR(
      DataScope.ALL,
      Permission.KOL_VIEW,
      Permission.PRODUCT_VIEW,
      Permission.PRODUCT_EDIT,
      Permission.PRODUCT_STATUS,
      Permission.ORDER_VIEW,
      Permission.ORDER_MANAGE,
      Permission.DASHBOARD_SHOP,
      Permission.DASHBOARD_KOL,
      Permission.DATA_EXPORT),
  BD(
      DataScope.OWN,
      Permission.KOL_VIEW,
      Permission.KOL_EDIT,
      Permission.KOL_REGISTER_LINK,
      Permission.KOL_COPY_TOKEN,
      Permission.PRODUCT_VIEW,
      Permission.ORDER_VIEW,
      Permission.COMMISSION_VIEW,
      Permission.PAYOUT_ACCOUNT_REVIEW,
      Permission.WITHDRAWAL_REVIEW,
      Permission.PREPAY_REVIEW,
      Permission.PREPAY_APPLY,
      Permission.DASHBOARD_TEAM,
      Permission.DASHBOARD_KOL,
      Permission.DATA_EXPORT);

  private final DataScope dataScope;
  private final Set<Permission> permissions;

  PostTemplate(DataScope dataScope, Permission... permissions) {
    this.dataScope = dataScope;
    this.permissions = EnumSet.copyOf(List.of(permissions));
  }

  public DataScope dataScope() {
    return dataScope;
  }

  /** Default permission codes, in {@link Permission} declaration order. */
  public List<String> permissionCodes() {
    return Permission.ordered(permissions);
  }
}
