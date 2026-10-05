package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.SocialAccountDTO;
import java.util.List;
import java.util.Objects;

final class KolRequests {
  private KolRequests() {}

  static List<SocialAccountDTO> social(List<SocialAccountRequest> accounts) {
    return accounts == null
        ? List.of()
        : accounts.stream().filter(Objects::nonNull).map(SocialAccountRequest::toDto).toList();
  }
}
