package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record KolDTO(
    Long id,
    String kolCode,
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    BigDecimal defaultRate,
    Integer status,
    String remark,
    Instant createTime,
    Instant updateTime) {}
