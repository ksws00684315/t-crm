package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.SocialAccountDTO;

/** {@code followers} is serialized as a string like every Long in the API. */
public record SocialAccountVO(String platform, String handle, String url, Long followers) {
  static SocialAccountVO from(SocialAccountDTO s) {
    return new SocialAccountVO(s.platform(), s.handle(), s.url(), s.followers());
  }
}
