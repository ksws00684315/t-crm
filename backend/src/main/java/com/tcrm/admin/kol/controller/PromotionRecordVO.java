package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import java.time.Instant;
import java.util.Map;

public record PromotionRecordVO(
    String changeType,
    Map<String, Object> before,
    Map<String, Object> after,
    String remark,
    String operatorName,
    Instant createTime,
    Integer versionNo,
    Instant effectiveTime) {
  static PromotionRecordVO from(KolPromotionService.PromotionRecordDTO d) {
    return new PromotionRecordVO(
        d.changeType(),
        d.before(),
        d.after(),
        d.remark(),
        d.operatorName(),
        d.createTime(),
        d.versionNo(),
        d.effectiveTime());
  }
}
