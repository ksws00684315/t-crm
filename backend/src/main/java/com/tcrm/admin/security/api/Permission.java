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
 *
 * <p>Every code belongs to one company edition ({@link #codesOf(Edition)}, ADR-038 §2.4): the
 * {@code KOL_LIBRARY} codes are the creator library only, every other code is the distribution
 * product. Super administrators, assignment and the permission catalog are all limited to the codes
 * of the current company's edition.
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
  WITHDRAWAL_PAY("withdrawal:pay", Group.FUNDS),
  PREPAY_APPLY("prepay:apply", Group.FUNDS),
  PREPAY_REVIEW("prepay:review", Group.FUNDS),
  PREPAY_FINANCE_REVIEW("prepay:finance-review", Group.FUNDS),
  PREPAY_PAY("prepay:pay", Group.FUNDS),
  DASHBOARD_SHOP("dashboard:shop", Group.DASHBOARD),
  DASHBOARD_TEAM("dashboard:team", Group.DASHBOARD),
  DASHBOARD_KOL("dashboard:kol", Group.DASHBOARD),
  DATA_EXPORT("data:export", Group.OTHER),
  KOL_LIBRARY_VIEW("kol-library:view", Group.KOL_LIBRARY),
  KOL_LIBRARY_EDIT("kol-library:edit", Group.KOL_LIBRARY);

  /** Group names are the API values: KOL, PRODUCT, ORDER, FUNDS, DASHBOARD, OTHER, KOL_LIBRARY. */
  public enum Group {
    KOL,
    PRODUCT,
    ORDER,
    FUNDS,
    DASHBOARD,
    OTHER,
    KOL_LIBRARY
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

  /** Codes of one edition, in enum order (ADR-038 §2.4). */
  public static List<String> codesOf(Edition edition) {
    return Arrays.stream(values()).filter(p -> p.edition() == edition).map(p -> p.code).toList();
  }

  /** The edition this code belongs to. */
  public Edition edition() {
    return group == Group.KOL_LIBRARY ? Edition.KOL_LIBRARY : Edition.DISTRIBUTION;
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
