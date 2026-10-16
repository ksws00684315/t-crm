package com.tcrm.admin.kol.service;

/** New link template; the channel is fixed to WEIDIAN and the status starts enabled. */
public record CreateLinkTemplateCommand(
    String templateCode, String templateName, String templateUrl, Integer sortOrder) {}
