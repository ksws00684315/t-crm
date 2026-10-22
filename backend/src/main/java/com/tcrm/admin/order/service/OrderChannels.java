package com.tcrm.admin.order.service;

/**
 * Sales channels the order module knows (task 40). Weidian is the only channel today; the value set
 * waits for M0 (progress rows 09.01/09.02).
 */
public final class OrderChannels {
  public static final String WEIDIAN = "WEIDIAN";

  private OrderChannels() {}

  /** True when the filter value selects every order (no channel restriction applies). */
  public static boolean known(String channel) {
    return channel == null || channel.isBlank() || WEIDIAN.equals(channel);
  }
}
