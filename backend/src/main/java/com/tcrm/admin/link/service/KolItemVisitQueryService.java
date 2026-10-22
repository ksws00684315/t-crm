package com.tcrm.admin.link.service;

import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.KolItemVisitRow;
import com.tcrm.admin.product.api.KolItemVisitQuery;
import com.tcrm.admin.product.api.KolItemVisitTotal;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** Link-module implementation of creator-item visit counts (task 39). */
@Service
public class KolItemVisitQueryService implements KolItemVisitQuery {
  private final ClickLogMapper clicks;

  public KolItemVisitQueryService(ClickLogMapper clicks) {
    this.clicks = clicks;
  }

  @Override
  public List<KolItemVisitTotal> countByKolItemIds(Collection<Long> kolItemIds) {
    if (kolItemIds == null || kolItemIds.isEmpty()) {
      return List.of();
    }
    return clicks.countVisitsByKolItemIds(kolItemIds).stream()
        .map(KolItemVisitQueryService::toTotal)
        .toList();
  }

  private static KolItemVisitTotal toTotal(KolItemVisitRow row) {
    return new KolItemVisitTotal(
        row.getKolItemId(), row.getVisitCount() == null ? 0L : row.getVisitCount());
  }
}
