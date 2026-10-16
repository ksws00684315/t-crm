package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.BdTransferStatus;

public record BdTransferFilterDTO(
    BdTransferStatus status,
    String kolCode,
    Long fromBdUserId,
    Long toBdUserId,
    int page,
    int size) {}
