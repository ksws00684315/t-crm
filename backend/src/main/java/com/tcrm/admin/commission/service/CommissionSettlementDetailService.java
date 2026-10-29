package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi;
import com.tcrm.admin.order.api.OrderSettlementApi.ItemRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi.OrderRefDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement detail list (task 62, requirement 13.05, design §6.2): per-order clearing with the
 * UNVERIFIED / FROZEN / POSTED accounting status (task 81). BD OWN scope sees only orders whose
 * attributed lines all belong to their own KOLs (mixed attribution stays hidden).
 */
@Service
public class CommissionSettlementDetailService {
  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final OrderSettlementApi orderSettlement;
  private final KolApi kols;
  private final ProductAccessApi products;
  private final ShopAccessApi shops;

  public CommissionSettlementDetailService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      OrderSettlementApi orderSettlement,
      KolApi kols,
      ProductAccessApi products,
      ShopAccessApi shops) {
    this.states = states;
    this.revisions = revisions;
    this.orderSettlement = orderSettlement;
    this.kols = kols;
    this.products = products;
    this.shops = shops;
  }

  /** Query of the settlement detail list; {@code channel} accepts only WEIDIAN. */
  public record DetailFilter(String orderNo, String channel, Long shopId, int page, int size) {}

  /**
   * One per-order clearing row. {@code weidianWithdrawable} is the Weidian settlement flow total
   * (display only, null before settlement); {@code status} is UNVERIFIED, FROZEN or POSTED.
   */
  public record SettlementRow(
      long id,
      String orderNo,
      String channel,
      String shop,
      List<Long> kolIds,
      List<String> kolNames,
      String payment,
      String refund,
      String weidianWithdrawable,
      String commission,
      List<String> rates,
      String status,
      java.time.Instant businessAt,
      List<Line> lines) {}

  /** Per-item commission line inside a settlement row. Names come from the order line. */
  public record Line(
      long id,
      String orderNo,
      long itemId,
      String product,
      String sku,
      String image,
      String base,
      String rate,
      Integer rateVersion,
      Long ruleVersion,
      String commission,
      String adjustment,
      String releaseBasis,
      java.time.Instant businessAt,
      String direction,
      String subject,
      Long kolId,
      String kol) {}

  @Transactional(readOnly = true)
  public PageResult<SettlementRow> page(AccountDTO user, DetailFilter filter) {
    if (user == null || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (filter.channel() != null
        && !filter.channel().isBlank()
        && !"WEIDIAN".equals(filter.channel())) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          List<Long> ownKolIds = null;
          if (user.ownScope()) {
            ownKolIds =
                kols.listByBdUserId(user.id()).stream()
                    .map(KolRefDTO::id)
                    .filter(Objects::nonNull)
                    .toList();
            if (ownKolIds.isEmpty()) {
              return new PageResult<>(List.of(), 0, filter.page(), filter.size());
            }
          }
          PageResult<Long> orderIds =
              orderSettlement.pageOrderIds(
                  filter.orderNo(), filter.shopId(), ownKolIds, filter.page(), filter.size());
          if (orderIds.items().isEmpty()) {
            return new PageResult<>(List.of(), orderIds.total(), filter.page(), filter.size());
          }
          List<SettlementRow> rows = buildRows(orderIds.items());
          return new PageResult<>(rows, orderIds.total(), filter.page(), filter.size());
        });
  }

  private List<SettlementRow> buildRows(List<Long> orderIds) {
    Map<Long, OrderRefDTO> orders = new HashMap<>();
    for (OrderRefDTO order : orderSettlement.listOrdersByIds(orderIds)) {
      orders.put(order.id(), order);
    }
    List<ItemRefDTO> itemRefs = orderSettlement.listItemsByOrderIds(orderIds);
    Map<Long, List<ItemRefDTO>> itemsByOrder = new HashMap<>();
    Set<Long> productIds = new HashSet<>();
    for (ItemRefDTO item : itemRefs) {
      itemsByOrder.computeIfAbsent(item.orderId(), k -> new ArrayList<>()).add(item);
      if (item.productId() != null) {
        productIds.add(item.productId());
      }
    }
    Map<Long, String> images = new HashMap<>();
    for (var product : products.listProducts(productIds)) {
      images.put(product.id(), product.imageUrl());
    }
    Map<Long, ShopAccessDTO> shopsById = new HashMap<>();
    for (ShopAccessDTO shop : shops.listShops()) {
      shopsById.put(shop.id(), shop);
    }

    List<CommissionItemStateDO> stateRows = states.listByOrderIds(orderIds);
    Map<Long, List<CommissionItemStateDO>> statesByOrder = new HashMap<>();
    Set<Long> stateIds = new HashSet<>();
    Set<Long> kolIds = new HashSet<>();
    for (CommissionItemStateDO row : stateRows) {
      statesByOrder.computeIfAbsent(row.getOrderId(), k -> new ArrayList<>()).add(row);
      stateIds.add(row.getId());
      if (row.getKolId() != null) {
        kolIds.add(row.getKolId());
      }
    }
    Map<Long, CommissionItemRevisionDO> latestRevisions = new HashMap<>();
    if (!stateIds.isEmpty()) {
      for (CommissionItemRevisionDO revision : revisions.listLatestByItemStateIds(stateIds)) {
        latestRevisions.put(revision.getItemStateId(), revision);
      }
    }
    Map<Long, KolRefDTO> kolsById = new HashMap<>();
    for (KolRefDTO kol : kols.listByIds(kolIds)) {
      kolsById.put(kol.id(), kol);
    }

    Map<Long, BigDecimal> withdrawable = orderSettlement.weidianWithdrawableByOrderIds(orderIds);
    List<SettlementRow> rows = new ArrayList<>(orderIds.size());
    for (Long orderId : orderIds) {
      OrderRefDTO order = orders.get(orderId);
      List<ItemRefDTO> items = itemsByOrder.getOrDefault(orderId, List.of());
      List<CommissionItemStateDO> orderStates = statesByOrder.getOrDefault(orderId, List.of());
      if (order == null) {
        continue;
      }
      rows.add(
          buildRow(
              order,
              items,
              orderStates,
              latestRevisions,
              kolsById,
              images,
              shopsById,
              withdrawable.get(orderId)));
    }
    return rows;
  }

  private SettlementRow buildRow(
      OrderRefDTO order,
      List<ItemRefDTO> items,
      List<CommissionItemStateDO> orderStates,
      Map<Long, CommissionItemRevisionDO> latestRevisions,
      Map<Long, KolRefDTO> kolsById,
      Map<Long, String> images,
      Map<Long, ShopAccessDTO> shopsById,
      BigDecimal withdrawable) {
    BigDecimal payment =
        order.payAmount() == null ? BigDecimal.ZERO.setScale(2) : order.payAmount();
    BigDecimal refund = BigDecimal.ZERO.setScale(2);
    for (ItemRefDTO item : items) {
      refund = refund.add(item.refundAmount() == null ? BigDecimal.ZERO : item.refundAmount());
    }
    BigDecimal commission = BigDecimal.ZERO.setScale(2);
    boolean unverified = false;
    boolean frozen = false;
    Set<Long> rowKolIds = new TreeSet<>();
    TreeSet<String> rates = new TreeSet<>();
    List<Line> lines = new ArrayList<>(orderStates.size());
    for (CommissionItemStateDO row : orderStates) {
      commission = commission.add(row.getAccruedCommissionCny());
      unverified = unverified || !Boolean.TRUE.equals(row.getVerified());
      if (row.getKolId() != null) {
        rowKolIds.add(row.getKolId());
      }
      CommissionItemRevisionDO revision = latestRevisions.get(row.getId());
      BigDecimal rate = revision == null ? null : revision.getRate();
      if (rate != null) {
        rates.add(rate.toPlainString());
      }
      BigDecimal released =
          row.getReleasedCommissionCny() == null ? BigDecimal.ZERO : row.getReleasedCommissionCny();
      frozen = frozen || row.getAccruedCommissionCny().compareTo(released) > 0;
      BigDecimal adjustment = released.subtract(row.getAccruedCommissionCny());
      KolRefDTO kol = row.getKolId() == null ? null : kolsById.get(row.getKolId());
      ItemRefDTO item =
          items.stream().filter(i -> i.id() == row.getOrderItemId()).findFirst().orElse(null);
      lines.add(
          new Line(
              row.getId(),
              order.orderNo(),
              row.getOrderItemId(),
              item == null ? null : textOrNull(item.itemName()),
              item == null ? null : textOrNull(item.skuName()),
              item == null || item.productId() == null ? null : images.get(item.productId()),
              row.getBasisCny() == null ? null : row.getBasisCny().toPlainString(),
              rate == null ? null : rate.toPlainString(),
              row.getConfigVersion(),
              row.getRuleVersionId(),
              row.getAccruedCommissionCny().toPlainString(),
              adjustment.toPlainString(),
              row.getReleaseBasis(),
              order.payTime(),
              adjustment.signum() < 0 ? "DEBIT" : "CREDIT",
              row.getKolId() == null ? "COMPANY" : "KOL",
              row.getKolId(),
              kol == null ? null : kol.name()));
    }
    List<String> kolNames = new ArrayList<>();
    for (Long kolId : rowKolIds) {
      KolRefDTO kol = kolsById.get(kolId);
      kolNames.add(kol == null ? String.valueOf(kolId) : kol.name());
    }
    ShopAccessDTO shop = shopsById.get(order.shopId());
    return new SettlementRow(
        order.id(),
        order.orderNo(),
        "WEIDIAN",
        shop == null ? null : shop.name(),
        List.copyOf(rowKolIds),
        kolNames,
        payment.toPlainString(),
        refund.toPlainString(),
        withdrawable == null ? null : withdrawable.toPlainString(),
        commission.toPlainString(),
        List.copyOf(rates),
        rowStatus(unverified, frozen),
        order.payTime(),
        lines);
  }

  /**
   * UNVERIFIED while any line is unverified; FROZEN while any line still has accrued commission
   * waiting for release (task 81: accrual starts at payment); POSTED once every line is released.
   */
  private static String rowStatus(boolean unverified, boolean frozen) {
    if (unverified) {
      return "UNVERIFIED";
    }
    return frozen ? "FROZEN" : "POSTED";
  }

  /** Blank order-line names (a line with no SKU) stay null so the client can tell them apart. */
  private static String textOrNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
