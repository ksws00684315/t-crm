package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.service.CopyTokenFilterDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** List filters; blank strings become null. */
public record CopyTokenQuery(
    String hint,
    @Min(1) Long kolId,
    String targetChannel,
    String status,
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size) {

  CopyTokenFilterDTO toFilter() {
    return new CopyTokenFilterDTO(
        blankToNull(hint),
        kolId,
        blankToNull(targetChannel),
        parseStatus(status),
        page == null ? 1 : page,
        size == null ? 20 : size);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static CopyTokenDisplayStatus parseStatus(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return CopyTokenDisplayStatus.valueOf(value.strip());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }
}
