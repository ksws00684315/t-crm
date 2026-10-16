package com.tcrm.admin.commission.service;

/**
 * Masking of payout accounts (task 15a §4.2.7): an e-mail keeps its first two local characters and
 * the domain; any other string keeps the first and last four characters with an ellipsis, and
 * strings of at most eight characters keep only the first and last one. Every list and log shows
 * the masked value; only the KOL's own list and the reviewer's detail view decrypt.
 */
public final class AccountMasker {
  private static final String ELLIPSIS = "…";
  private static final int EMAIL_PREFIX = 2;
  private static final int PREFIX = 4;
  private static final int SUFFIX = 4;
  private static final int SHORT = 8;

  private AccountMasker() {}

  /** Masks a PayPal account or wallet address; e-mail shape uses the e-mail rule. */
  public static String mask(String account) {
    if (account == null || account.isEmpty()) {
      return account;
    }
    int at = account.indexOf('@');
    if (at > 0 && account.indexOf('@', at + 1) < 0 && at < account.length() - 1) {
      String local = account.substring(0, at);
      if (local.length() <= EMAIL_PREFIX) {
        return "***" + account.substring(at);
      }
      return local.substring(0, EMAIL_PREFIX) + "***" + account.substring(at);
    }
    if (account.length() <= SHORT) {
      return account.charAt(0) + ELLIPSIS + account.charAt(account.length() - 1);
    }
    return account.substring(0, PREFIX) + ELLIPSIS + account.substring(account.length() - SUFFIX);
  }
}
