package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolOwnershipDTO;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KolOwnershipLookup implements KolOwnershipApi {
  private final KolBdHistoryMapper history;

  public KolOwnershipLookup(KolBdHistoryMapper history) {
    this.history = history;
  }

  @Override
  public Long bdAt(Long kolId, Instant at) {
    var row =
        history.selectOne(
            new LambdaQueryWrapper<KolBdHistoryDO>()
                .eq(KolBdHistoryDO::getKolId, kolId)
                .le(KolBdHistoryDO::getStartTime, at)
                .and(
                    q ->
                        q.isNull(KolBdHistoryDO::getEndTime)
                            .or()
                            .gt(KolBdHistoryDO::getEndTime, at))
                .orderByDesc(KolBdHistoryDO::getStartTime)
                .last("LIMIT 1"));
    return row == null ? null : row.getBdUserId();
  }

  @Override
  public List<KolOwnershipDTO> createdBetween(Instant from, Instant to) {
    return history
        .selectList(
            new LambdaQueryWrapper<KolBdHistoryDO>()
                .ge(KolBdHistoryDO::getCreateTime, from)
                .lt(KolBdHistoryDO::getCreateTime, to)
                .orderByAsc(KolBdHistoryDO::getCreateTime, KolBdHistoryDO::getId))
        .stream()
        .map(
            h ->
                new KolOwnershipDTO(
                    h.getKolId(), h.getBdUserId(), h.getStartTime(), h.getEndTime()))
        .toList();
  }
}
