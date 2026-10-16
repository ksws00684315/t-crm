package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.service.BdTransferFilterDTO;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record BdTransferQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    BdTransferStatus status,
    @Size(max = 16) String kolCode,
    @Min(1) Long fromBdUserId,
    @Min(1) Long toBdUserId) {
  public BdTransferQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }

  BdTransferFilterDTO toFilter() {
    return new BdTransferFilterDTO(status, kolCode, fromBdUserId, toBdUserId, page, size);
  }
}
