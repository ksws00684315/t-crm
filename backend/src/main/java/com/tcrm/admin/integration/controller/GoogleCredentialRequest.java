package com.tcrm.admin.integration.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full content of the service account JSON key file. The field name contains "credential", so the
 * operation log redacts it.
 */
public record GoogleCredentialRequest(@NotBlank @Size(max = 8192) String credentialJson) {
  @Override
  public String toString() {
    return "GoogleCredentialRequest[credentialJson=redacted]";
  }
}
