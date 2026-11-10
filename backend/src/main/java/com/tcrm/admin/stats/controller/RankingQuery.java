package com.tcrm.admin.stats.controller;

/** Paging and sorting for a management ranking. Validation is shared by list and export. */
public record RankingQuery(Integer page, Integer size, String sortBy, String sortDirection) {}
