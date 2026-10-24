package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.BizFileService;

/** Attachment metadata on an admin prepay detail. */
public record PrepayFileVO(Long id, String name, String mimeType, Long size) {
  static PrepayFileVO from(BizFileService.FileMeta meta) {
    return new PrepayFileVO(meta.id(), meta.name(), meta.mimeType(), meta.size());
  }
}
