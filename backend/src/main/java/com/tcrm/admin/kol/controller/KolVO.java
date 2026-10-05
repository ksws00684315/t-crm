package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolDTO;
import com.tcrm.admin.kol.service.KolRules;
import java.time.Instant;
import java.util.List;

/** {@code defaultRate} is a fraction with 4 decimals ("0.1550"), null when not set. */
public record KolVO(
    Long id,
    String kolCode,
    String name,
    String email,
    String country,
    List<SocialAccountVO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    String defaultRate,
    Integer status,
    String remark,
    Instant createTime,
    Instant updateTime) {
  static KolVO from(KolDTO k) {
    return new KolVO(
        k.id(),
        k.kolCode(),
        k.name(),
        k.email(),
        k.country(),
        k.socialAccounts().stream().map(SocialAccountVO::from).toList(),
        k.bdUserId(),
        k.bdUserName(),
        KolRules.rateText(k.defaultRate()),
        k.status(),
        k.remark(),
        k.createTime(),
        k.updateTime());
  }
}
