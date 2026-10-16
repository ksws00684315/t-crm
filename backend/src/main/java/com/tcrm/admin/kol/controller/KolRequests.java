package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.SocialAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;
import java.util.Objects;

final class KolRequests {
  private KolRequests() {}

  /** DTOs of the given accounts; more than one primary account is a 400 (task 11 §4.3). */
  static List<SocialAccountDTO> social(List<SocialAccountRequest> accounts) {
    List<SocialAccountDTO> social =
        accounts == null
            ? List.of()
            : accounts.stream().filter(Objects::nonNull).map(SocialAccountRequest::toDto).toList();
    if (social.stream().filter(SocialAccountDTO::primary).count() > 1) {
      throw new BusinessException(ErrorCode.KOL_MULTIPLE_PRIMARY);
    }
    return social;
  }

  /** Strips and blank-to-null for optional single-line profile fields. */
  static String text(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
