package com.tcrm.admin.integration.service;

import java.security.PrivateKey;

/** Decrypted service account used to sign token requests; never logged or returned. */
record GoogleServiceAccount(String clientEmail, String privateKeyId, PrivateKey privateKey) {
  @Override
  public String toString() {
    return "GoogleServiceAccount[clientEmail=%s, privateKey=redacted]".formatted(clientEmail);
  }
}
