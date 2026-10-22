package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.service.DepartureService;

/** One selectable heir: a BD that may inherit another BD's creators. */
public record DepartureHeirVO(Long id, String username, String nickname) {
  public static DepartureHeirVO from(DepartureService.HeirDTO heir) {
    return new DepartureHeirVO(heir.id(), heir.username(), heir.nickname());
  }
}
