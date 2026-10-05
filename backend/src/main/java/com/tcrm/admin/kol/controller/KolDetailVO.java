package com.tcrm.admin.kol.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tcrm.admin.kol.service.KolDetailDTO;
import java.util.List;

/** KOL fields plus the BD ownership history, newest period first. */
public record KolDetailVO(@JsonUnwrapped KolVO kol, List<BdHistoryVO> bdHistory) {
  static KolDetailVO from(KolDetailDTO d) {
    return new KolDetailVO(
        KolVO.from(d.kol()), d.bdHistory().stream().map(BdHistoryVO::from).toList());
  }
}
