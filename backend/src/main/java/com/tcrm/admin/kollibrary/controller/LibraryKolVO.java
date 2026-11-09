package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryKolDTO;
import java.time.Instant;
import java.util.List;

/** A creator row of the list (requirement 1.2.5 ②): basic data and status only. */
public record LibraryKolVO(
    Long id,
    String platform,
    String kolUsername,
    String displayName,
    Long followers,
    String countryCode,
    List<String> categories,
    String grade,
    String cooperationStage,
    String outreachStatus,
    String followUpOwner,
    Instant updateTime) {

  public static LibraryKolVO from(LibraryKolDTO dto) {
    return new LibraryKolVO(
        dto.id(),
        dto.platform(),
        dto.kolUsername(),
        dto.displayName(),
        dto.followers(),
        dto.countryCode(),
        dto.categories(),
        dto.grade(),
        dto.cooperationStage(),
        dto.outreachStatus(),
        dto.followUpOwner(),
        dto.updateTime());
  }
}
