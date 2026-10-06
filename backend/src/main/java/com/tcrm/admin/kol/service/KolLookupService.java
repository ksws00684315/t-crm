package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** KOL facade for other modules (task 05 §4.3). */
@Service
public class KolLookupService implements KolApi {
  private final KolMapper kols;

  public KolLookupService(KolMapper kols) {
    this.kols = kols;
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
    return toDto(kol);
  }

  @Override
  public List<KolRefDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return kols.selectList(new LambdaQueryWrapper<KolDO>().in(KolDO::getId, ids)).stream()
        .map(KolLookupService::toDto)
        .toList();
  }

  @Override
  public List<KolRefDTO> listAll() {
    return kols
        .selectList(
            new LambdaQueryWrapper<KolDO>()
                .select(
                    KolDO::getId,
                    KolDO::getKolCode,
                    KolDO::getName,
                    KolDO::getStatus,
                    KolDO::getBdUserId)
                .orderByAsc(KolDO::getId))
        .stream()
        .map(KolLookupService::toDto)
        .toList();
  }

  private static KolRefDTO toDto(KolDO kol) {
    if (kol == null) {
      return null;
    }
    return new KolRefDTO(
        kol.getId(),
        kol.getKolCode(),
        kol.getName(),
        kol.getStatus() != null && kol.getStatus() == 1,
        kol.getBdUserId());
  }
}
