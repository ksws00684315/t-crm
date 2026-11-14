package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderEvidenceApi;
import com.tcrm.admin.order.mapper.OrderEvidenceMapper;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderEvidenceService implements OrderEvidenceApi {
  private final OrderEvidenceMapper evidence;

  public OrderEvidenceService(OrderEvidenceMapper evidence) {
    this.evidence = evidence;
  }

  @Override
  public PageResult<EvidenceRow> page(Selection selection, Kind kind, int page, int size) {
    if (selection.scope().visibleShopIds().isEmpty()) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    long offset = (long) (page - 1) * size;
    var rows = evidence.page(selection, kind, size, offset);
    long total =
        rows.size() < size && (page == 1 || !rows.isEmpty())
            ? offset + rows.size()
            : evidence.count(selection, kind);
    return new PageResult<>(rows, total, page, size);
  }

  @Override
  public PageResult<ProductGroup> productGroups(
      Selection selection, ProductBreakdown group, int page, int size) {
    if (selection.scope().visibleShopIds().isEmpty()) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    long offset = (long) (page - 1) * size;
    var rows = evidence.productGroups(selection, group, size, offset);
    long total =
        rows.size() < size && (page == 1 || !rows.isEmpty())
            ? offset + rows.size()
            : evidence.countProductGroups(selection, group);
    return new PageResult<>(rows, total, page, size);
  }

  @Override
  public List<DailyAmount> daily(Selection selection) {
    return selection.scope().visibleShopIds().isEmpty() ? List.of() : evidence.daily(selection);
  }
}
