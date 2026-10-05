package com.tcrm.admin.kol.service;

import java.time.Instant;

/** One ownership period; null BD means unassigned, null end time means current. */
public record BdHistoryDTO(Long bdUserId, String bdUserName, Instant startTime, Instant endTime) {}
