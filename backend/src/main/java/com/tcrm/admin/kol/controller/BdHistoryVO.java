package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.BdHistoryDTO;
import java.time.Instant;

/** One ownership period; null {@code bdUserId} means unassigned, null {@code endTime} current. */
public record BdHistoryVO(Long bdUserId, String bdUserName, Instant startTime, Instant endTime) {
  static BdHistoryVO from(BdHistoryDTO h) {
    return new BdHistoryVO(h.bdUserId(), h.bdUserName(), h.startTime(), h.endTime());
  }
}
