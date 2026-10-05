package com.tcrm.admin.kol.service;

import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Storage format of crm_kol.social_accounts. Uses its own mapper so stored numbers stay numbers
 * (the API mapper writes Long values as strings).
 */
final class SocialAccountsJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<SocialAccountDTO>> TYPE = new TypeReference<>() {};

  private SocialAccountsJson() {}

  static String write(List<SocialAccountDTO> accounts) {
    return JSON.writeValueAsString(accounts == null ? List.of() : accounts);
  }

  static List<SocialAccountDTO> read(String json) {
    return json == null || json.isBlank() ? List.of() : JSON.readValue(json, TYPE);
  }
}
