package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** List filters; {@code bdUserId} is ignored for a BD (OWN) caller. */
public record KolQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    @Size(max = 128) String keyword,
    @Size(max = 64) String kolCode,
    @Size(max = 128) String name,
    @Size(max = 254) String email,
    @Pattern(regexp = "[A-Za-z]{2}") String country,
    @Min(0) @Max(1) Integer status,
    @Min(1) Long bdUserId,
    @Pattern(regexp = "PENDING|APPROVED") String reviewStatus,
    @Pattern(regexp = "PENDING_PROMOTION|PROMOTING|INVALID|PENDING_UPDATE") String relationStatus,
    @Pattern(regexp = "FROZEN|NORMAL_OR_ABNORMAL") String loginStatus) {
  public KolQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }

  KolFilterDTO toFilter() {
    return new KolFilterDTO(
        keyword,
        kolCode,
        name,
        email,
        country,
        status,
        bdUserId,
        reviewStatus,
        relationStatus,
        loginStatus,
        page,
        size);
  }
}
