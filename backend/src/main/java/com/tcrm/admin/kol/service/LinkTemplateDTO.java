package com.tcrm.admin.kol.service;

/** One agent link template of the current company (task 11 §4.2). */
public record LinkTemplateDTO(
    Long id,
    String templateCode,
    String templateName,
    String templateUrl,
    String channelCode,
    Integer sortOrder,
    Integer status) {}
