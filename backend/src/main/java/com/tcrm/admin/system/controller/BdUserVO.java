package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.api.BdUserDTO;

public record BdUserVO(Long id, String username, String nickname) {
  public static BdUserVO from(BdUserDTO u) {
    return new BdUserVO(u.id(), u.username(), u.nickname());
  }
}
