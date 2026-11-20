package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderEvidenceApi;
import com.tcrm.admin.order.mapper.OrderEvidenceMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderEvidenceService implements OrderEvidenceApi {
  private final OrderEvidenceMapper evidence;

  private final OrderSettlementFlowMapper settlements;

  public OrderEvidenceService(OrderEvidenceMapper evidence, OrderSettlementFlowMapper settlements) {
    this.evidence = evidence;
    this.settlements = settlements;
  }

  @Override
  public PageResult<SettlementFlowRow> shopSettlementFlows(
      long tenantId, long shopId, Instant from, Instant until, int page, int size) {
    long offset = (long) (page - 1) * size;
    var rows = settlements.pageShopReceivables(tenantId, shopId, from, until, size, offset);
    long total =
        rows.size() < size && (page == 1 || !rows.isEmpty())
            ? offset + rows.size()
            : settlements.countShopReceivables(tenantId, shopId, from, until);
    return new PageResult<>(rows, total, page, size);
  }

  @Override
  public PageResult<EvidenceRow> page(Selection selection, Kind kind, int page, int size) {
    return page(selection, kind, page, size, null, false);
  }

  @Override
  public PageResult<EvidenceRow> sortedActivity(
      Selection selection, Kind kind, int page, int size, DetailSort sortBy, boolean ascending) {
    return page(selection, kind, page, size, sortBy, ascending);
  }

  private PageResult<EvidenceRow> page(
      Selection selection, Kind kind, int page, int size, DetailSort sortBy, boolean ascending) {
    if (selection.scope().visibleShopIds().isEmpty()) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    long offset = (long) (page - 1) * size;
    var rows = evidence.pageWithSort(selection, kind, size, offset, sortBy, ascending);
    long total =
        rows.size() < size && (page == 1 || !rows.isEmpty())
            ? offset + rows.size()
            : evidence.count(selection, kind);
    return new PageResult<>(rows, total, page, size);
  }

  @Override
  public PageResult<ProductGroup> productGroups(
      Selection selection, ProductBreakdown group, int page, int size) {
    return sortedProductGroups(selection, group, page, size, null, false);
  }

  @Override
  public PageResult<ProductGroup> sortedProductGroups(
      Selection selection,
      ProductBreakdown group,
      int page,
      int size,
      DetailSort sortBy,
      boolean ascending) {
    if (selection.scope().visibleShopIds().isEmpty()) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    long offset = (long) (page - 1) * size;
    var rows = evidence.productGroupsWithSort(selection, group, size, offset, sortBy, ascending);
    long total =
        rows.size() < size && (page == 1 || !rows.isEmpty())
            ? offset + rows.size()
            : evidence.countProductGroups(selection, group);
    return new PageResult<>(rows, total, page, size);
  }

  @Override
  public ShopCreatorCounts shopCreatorCounts(Selection selection) {
    return evidence.shopCreatorCounts(selection);
  }

  @Override
  public ProductOrderCounts productOrderCounts(Selection selection) {
    return selection.scope().visibleShopIds().isEmpty()
        ? new ProductOrderCounts(0, 0)
        : evidence.productOrderCounts(selection);
  }

  @Override
  public List<DailyAmount> daily(Selection selection) {
    return selection.scope().visibleShopIds().isEmpty() ? List.of() : evidence.daily(selection);
  }
}
