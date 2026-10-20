package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.LinkTemplateDTO;
import java.time.Instant;

/** One agent link template; {@code status} 1 enabled, 0 disabled. */
public record LinkTemplateVO(
    Long id,
    String templateCode,
    String templateName,
    String templateUrl,
    String channelCode,
    Integer sortOrder,
    Integer status,
    Instant createTime) {
  static LinkTemplateVO from(LinkTemplateDTO t) {
    return new LinkTemplateVO(
        t.id(),
        t.templateCode(),
        t.templateName(),
        t.templateUrl(),
        t.channelCode(),
        t.sortOrder(),
        t.status(),
        t.createTime());
  }
}
