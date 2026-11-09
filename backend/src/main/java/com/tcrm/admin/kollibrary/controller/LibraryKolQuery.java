package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryKolFilter;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** List filters of requirement 1.2.5 ②; unknown option values simply match nothing. */
public record LibraryKolQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    @Size(max = 128) String keyword,
    @Size(max = 16) String platform,
    @Size(max = 8) String countryCode,
    @Size(max = 48) String category,
    @Size(max = 16) String outreachStatus,
    @Size(max = 64) String followUpOwner) {
  public LibraryKolQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }

  LibraryKolFilter toFilter() {
    return new LibraryKolFilter(
        keyword, platform, countryCode, category, outreachStatus, followUpOwner, page, size);
  }
}
