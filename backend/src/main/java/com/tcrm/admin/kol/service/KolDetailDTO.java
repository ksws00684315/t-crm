package com.tcrm.admin.kol.service;

import java.util.List;

/** KOL with its BD ownership history, newest period first. */
public record KolDetailDTO(KolDTO kol, List<BdHistoryDTO> bdHistory) {}
