package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryKolDTO;
import java.util.List;

/**
 * A creator row of the list (requirement 1.2.5 ②, revised 2026-11-10): the eight list columns only;
 * the list is still ordered by the last change, which is not returned.
 */
public record LibraryKolVO(
    Long id,
    String platform,
    String kolUsername,
    String displayName,
    Long followers,
    String countryCode,
    List<String> categories,
    String grade,
    String outreachStatus,
    String followUpOwner) {

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
        dto.outreachStatus(),
        dto.followUpOwner());
  }
}
