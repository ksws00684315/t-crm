package com.tcrm.admin.integration.controller;

import com.tcrm.admin.integration.service.GoogleCredentialService;
import java.time.Instant;

/** Service account summary; the private key is never returned. */
public record GoogleCredentialVO(
    boolean configured, String clientEmail, String privateKeyId, Instant updateTime) {
  static GoogleCredentialVO from(GoogleCredentialService.View v) {
    return new GoogleCredentialVO(
        v.configured(), v.clientEmail(), v.privateKeyId(), v.updateTime());
  }
}
