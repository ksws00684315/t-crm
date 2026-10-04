package com.tcrm.admin.security.api;

import cn.dev33.satoken.stp.StpLogic;

/** Independent KOL token namespace; administrator tokens never authenticate a KOL. */
public final class StpKolUtil {
  /** KOL-only login logic, configured separately from the administrator namespace. */
  public static final StpLogic STP = new StpLogic("kol");

  private StpKolUtil() {}
}
