package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.LinkTemplateMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.LinkTemplateDO;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** KOL facade for other modules (task 05 §4.3); resolves the link target inputs (task 11). */
@Service
public class KolLookupService implements KolApi {
  private final KolMapper kols;
  private final LinkTemplateMapper templates;

  public KolLookupService(KolMapper kols, LinkTemplateMapper templates) {
    this.kols = kols;
    this.templates = templates;
  }

  @Override
  public KolRefDTO getByCode(String kolCode) {
    if (kolCode == null || kolCode.isBlank()) {
      return null;
    }
    KolDO kol =
        kols.selectOne(
            new LambdaQueryWrapper<KolDO>()
                .eq(KolDO::getKolCode, kolCode.strip().toUpperCase(Locale.ROOT))
                .last("LIMIT 1"));
    return kol == null ? null : toDto(List.of(kol)).get(0);
  }

  @Override
  public List<KolRefDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return toDto(kols.selectList(new LambdaQueryWrapper<KolDO>().in(KolDO::getId, ids)));
  }

  @Override
  public List<KolRefDTO> listAll() {
    return toDto(
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .select(
                    KolDO::getId,
                    KolDO::getKolCode,
                    KolDO::getName,
                    KolDO::getStatus,
                    KolDO::getBdUserId,
                    KolDO::getInviteCode,
                    KolDO::getLinkTemplateId)
                .orderByAsc(KolDO::getId)));
  }

  @Override
  public List<Long> pageKolIdsWithTemplate(Long templateId, Long afterKolId, int limit) {
    return kols
        .selectList(
            new LambdaQueryWrapper<KolDO>()
                .select(KolDO::getId)
                .eq(KolDO::getLinkTemplateId, templateId)
                .gt(afterKolId != null, KolDO::getId, afterKolId)
                .orderByAsc(KolDO::getId)
                .last("LIMIT " + limit))
        .stream()
        .map(KolDO::getId)
        .toList();
  }

  @Override
  public List<KolRefDTO> listByBdUserId(Long bdUserId) {
    return toDto(kols.selectList(new LambdaQueryWrapper<KolDO>().eq(KolDO::getBdUserId, bdUserId)));
  }

  private List<KolRefDTO> toDto(List<KolDO> rows) {
    Map<Long, LinkTemplateDO> byId = templatesByIds(rows.stream().map(KolDO::getLinkTemplateId));
    return rows.stream()
        .map(
            kol -> {
              LinkTemplateDO template =
                  kol.getLinkTemplateId() == null ? null : byId.get(kol.getLinkTemplateId());
              return new KolRefDTO(
                  kol.getId(),
                  kol.getKolCode(),
                  kol.getName(),
                  enabled(kol),
                  kol.getBdUserId(),
                  kol.getInviteCode(),
                  template == null ? null : template.getTemplateUrl(),
                  template != null && Integer.valueOf(1).equals(template.getStatus()));
            })
        .toList();
  }

  private Map<Long, LinkTemplateDO> templatesByIds(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    if (wanted.isEmpty()) {
      return Map.of();
    }
    Map<Long, LinkTemplateDO> byId = new HashMap<>();
    for (LinkTemplateDO template : templates.selectBatchIds(wanted)) {
      byId.put(template.getId(), template);
    }
    return byId;
  }

  private static boolean enabled(KolDO kol) {
    return kol.getStatus() != null && kol.getStatus() == 1;
  }
}
