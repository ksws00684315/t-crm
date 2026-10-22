package com.tcrm.admin.security.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed permission codes (ADR-018). Names shown in the admin UI come from the language packs; this
 * enum is the only registry.
 */
public enum Permission {
  KOL_VIEW("kol:view", Group.KOL),
  KOL_EDIT("kol:edit", Group.KOL),
  KOL_REGISTER_LINK("kol:register-link", Group.KOL),
  KOL_COPY_TOKEN("kol:copy-token", Group.KOL),
  PRODUCT_VIEW("product:view", Group.PRODUCT),
  PRODUCT_EDIT("product:edit", Group.PRODUCT),
  PRODUCT_STATUS("product:status", Group.PRODUCT),
  ORDER_VIEW("order:view", Group.ORDER),
  ORDER_MANAGE("order:manage", Group.ORDER),
  COMMISSION_VIEW("commission:view", Group.FUNDS),
  COMMISSION_FREEZE("commission:freeze", Group.FUNDS),
  PAYOUT_ACCOUNT_REVIEW("payout-account:review", Group.FUNDS),
  WITHDRAWAL_REVIEW("withdrawal:review", Group.FUNDS),
  PREPAY_APPLY("prepay:apply", Group.FUNDS),
  PREPAY_REVIEW("prepay:review", Group.FUNDS),
  PREPAY_PAY("prepay:pay", Group.FUNDS),
  DASHBOARD_SHOP("dashboard:shop", Group.DASHBOARD),
  DASHBOARD_TEAM("dashboard:team", Group.DASHBOARD),
  DASHBOARD_KOL("dashboard:kol", Group.DASHBOARD),
  DATA_EXPORT("data:export", Group.OTHER);

  /** Group names are the API values: KOL, PRODUCT, ORDER, FUNDS, DASHBOARD, OTHER. */
  public enum Group {
    KOL,
    PRODUCT,
    ORDER,
    FUNDS,
    DASHBOARD,
    OTHER
  }

  private static final Map<String, Permission> BY_CODE = new LinkedHashMap<>();

  static {
    for (Permission permission : values()) {
      BY_CODE.put(permission.code, permission);
    }
  }

  private final String code;
  private final Group group;

  Permission(String code, Group group) {
    this.code = code;
    this.group = group;
  }

  public String code() {
    return code;
  }

  public Group group() {
    return group;
  }

  /** Every code, in enum order. */
  public static List<String> codes() {
    return Arrays.stream(values()).map(Permission::code).toList();
  }

  /** The permission for this code, or null when the code is not registered. */
  public static Permission fromCode(String code) {
    return BY_CODE.get(code);
  }

  /** Codes of {@code selected}, in enum order. */
  public static List<String> ordered(Set<Permission> selected) {
    EnumSet<Permission> set =
        selected.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(selected);
    return Arrays.stream(values()).filter(set::contains).map(Permission::code).toList();
  }
}
