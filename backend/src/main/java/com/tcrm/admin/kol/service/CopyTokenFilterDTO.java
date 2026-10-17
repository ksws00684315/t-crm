package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;

/** List filters for copy tokens (task 17a §4.4). */
public record CopyTokenFilterDTO(
    String hint,
    Long kolId,
    String targetChannel,
    CopyTokenDisplayStatus status,
    int page,
    int size) {}
