package com.tcrm.admin.security.api;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;

/**
 * Independent KOL token namespace; administrator tokens never authenticate a KOL. The static
 * wrappers mirror {@code StpUtil} so callers and tests read the same as the administrator side.
 */
public final class StpKolUtil {
  /** KOL-only login type; {@code @SaCheckLogin(type = TYPE)} routes here. */
  public static final String TYPE = "kol";

  /** KOL-only login logic, configured separately from the administrator namespace. */
  public static final StpLogic STP = new StpLogic(TYPE);

  private StpKolUtil() {}

  public static void checkLogin() {
    STP.checkLogin();
  }

  public static long getLoginIdAsLong() {
    return STP.getLoginIdAsLong();
  }

  public static void login(Object id) {
    STP.login(id);
  }

  public static void logout() {
    STP.logout();
  }

  public static void logout(Object id) {
    STP.logout(id);
  }

  public static void kickout(Object id) {
    STP.kickout(id);
  }

  public static void logoutByTokenValue(String tokenValue) {
    STP.logoutByTokenValue(tokenValue);
  }

  public static String getTokenValue() {
    return STP.getTokenValue();
  }

  public static String getTokenName() {
    return STP.getTokenName();
  }

  public static long getTokenTimeout() {
    return STP.getTokenTimeout();
  }

  public static SaSession getTokenSession() {
    return STP.getTokenSession();
  }
}
