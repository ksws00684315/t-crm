package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queries and human decisions, with tenant SQL filtering and payment-time OWN scope. */
@Service
public class OrderService {
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final RefundMapper refunds;
  private final OrderSyncStateMapper syncStates;
  private final KolApi kols;
  private final ShopAccessApi shops;
  private final BdUserApi bdUsers;
  private final WeidianAuthorizationApi authorization;
  private final OrderAttribution attribution;
  private final CurrentUserContext current;

  public OrderService(
      OrderMapper orders,
      OrderItemMapper items,
      RefundMapper refunds,
      OrderSyncStateMapper syncStates,
      KolApi kols,
      ShopAccessApi shops,
      BdUserApi bdUsers,
      WeidianAuthorizationApi authorization,
      OrderAttribution attribution,
      CurrentUserContext current) {
    this.orders = orders;
    this.items = items;
    this.refunds = refunds;
    this.syncStates = syncStates;
    this.kols = kols;
    this.shops = shops;
    this.bdUsers = bdUsers;
    this.authorization = authorization;
    this.attribution = attribution;
    this.current = current;
  }

  public PageResult<OrderDTO> page(OrderFilterDTO filter, boolean unmatched) {
    var user = current.user();
    var query =
        new LambdaQueryWrapper<OrderDO>()
            .eq(filter.shopId() != null, OrderDO::getShopId, filter.shopId())
            .ge(filter.payFrom() != null, OrderDO::getPayTime, filter.payFrom())
            .lt(filter.payTo() != null, OrderDO::getPayTime, filter.payTo())
            .eq(filter.status() != null, OrderDO::getOrderStatus, filter.status())
            .eq(filter.attribution() != null, OrderDO::getAttributionStatus, filter.attribution())
            .eq(user.ownScope(), OrderDO::getBdUserId, user.id());
    if (unmatched) {
      query.in(OrderDO::getAttributionStatus, List.of("CONFLICT", "UNMATCHED"));
    }
    if (filter.kolCode() != null && !filter.kolCode().isBlank()) {
      var kol = kols.getByCode(filter.kolCode());
      if (kol == null) {
        return new PageResult<>(List.of(), 0, filter.page(), filter.size());
      }
      query.eq(OrderDO::getKolId, kol.id());
    }
    query.orderByDesc(OrderDO::getPayTime, OrderDO::getId);
    var result = orders.selectPage(new Page<>(filter.page(), filter.size()), query);
    if (result.getRecords().isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), filter.page(), filter.size());
    }
    var ids = result.getRecords().stream().map(OrderDO::getId).toList();
    Map<Long, List<OrderItemDO>> lines =
        items
            .selectList(
                new LambdaQueryWrapper<OrderItemDO>()
                    .in(OrderItemDO::getOrderId, ids)
                    .orderByAsc(OrderItemDO::getId))
            .stream()
            .collect(Collectors.groupingBy(OrderItemDO::getOrderId));
    Map<Long, BigDecimal> totals =
        refunds
            .selectList(
                new LambdaQueryWrapper<RefundDO>()
                    .in(RefundDO::getOrderId, ids)
                    .eq(RefundDO::getRawStatus, "10"))
            .stream()
            .collect(Collectors.toMap(RefundDO::getOrderId, RefundDO::getAmount, BigDecimal::add));
    return new PageResult<>(
        decorate(result.getRecords(), lines, totals),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  /** Fills display names per page with a fixed number of batch lookups (task 19a §4.1.1). */
  private List<OrderDTO> decorate(
      List<OrderDO> records, Map<Long, List<OrderItemDO>> lines, Map<Long, BigDecimal> totals) {
    Map<Long, String> shopNames =
        shops.listShops().stream()
            .collect(Collectors.toMap(ShopAccessDTO::id, ShopAccessDTO::name, (a, b) -> a));
    Set<Long> kolIds = new HashSet<>();
    for (var order : records) {
      if (order.getKolId() != null) {
        kolIds.add(order.getKolId());
      }
      for (var line : lines.getOrDefault(order.getId(), List.of())) {
        if (line.getKolId() != null) {
          kolIds.add(line.getKolId());
        }
      }
    }
    Map<Long, KolRefDTO> kolById =
        kols.listByIds(kolIds).stream()
            .collect(Collectors.toMap(KolRefDTO::id, Function.identity()));
    Map<Long, BdUserDTO> bdById =
        bdUsers
            .listByIds(
                records.stream()
                    .map(OrderDO::getBdUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(BdUserDTO::id, Function.identity()));
    return records.stream()
        .map(
            o -> {
              var kol = o.getKolId() == null ? null : kolById.get(o.getKolId());
              return new OrderDTO(
                  o.getId(),
                  o.getShopId(),
                  o.getShopId() == null ? null : shopNames.get(o.getShopId()),
                  o.getWeidianOrderId(),
                  o.getKolId(),
                  kol == null ? null : kol.kolCode(),
                  kol == null ? null : kol.name(),
                  o.getBdUserId(),
                  displayName(bdById.get(o.getBdUserId())),
                  o.getOrderStatus(),
                  o.getAttributionStatus(),
                  o.getOrderTime(),
                  o.getPayTime(),
                  o.getTotalAmount(),
                  o.getPayAmount(),
                  totals.getOrDefault(o.getId(), BigDecimal.ZERO.setScale(2)),
                  lines.getOrDefault(o.getId(), List.of()).stream()
                      .map(line -> line(line, kolById))
                      .toList());
            })
        .toList();
  }

  /** Nickname first, then username; null when the snapshot assigns the order to the company. */
  private static String displayName(BdUserDTO user) {
    if (user == null) {
      return null;
    }
    return user.nickname() == null || user.nickname().isBlank() ? user.username() : user.nickname();
  }

  /** Every shop of the current company with its sync status, including never-synced ones. */
  public List<OrderSyncStateDTO> listSyncStates() {
    var authorized = authorization.authorizedShopIds();
    Map<Long, OrderSyncStateDO> states =
        syncStates.selectList(new LambdaQueryWrapper<OrderSyncStateDO>()).stream()
            .collect(Collectors.toMap(OrderSyncStateDO::getShopId, Function.identity()));
    return shops.listShops().stream()
        .map(
            shop -> {
              var state = states.get(shop.id());
              return new OrderSyncStateDTO(
                  shop.id(),
                  shop.name(),
                  Integer.valueOf(1).equals(shop.status()),
                  authorized.contains(shop.id()),
                  state == null ? null : state.getLastSuccessAt(),
                  state == null ? null : state.getLastError());
            })
        .toList();
  }

  @Transactional(rollbackFor = Exception.class)
  public void assign(Long id, Long kolId, boolean nonKol, String reason) {
    if (reason == null
        || reason.isBlank()
        || reason.length() > 255
        || (nonKol == (kolId != null))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var order = locked(id);
    if (order.getPayTime() == null) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    if (kolId != null && kols.listByIds(List.of(kolId)).isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var lines = lines(id);
    attribution.manual(order, lines, kolId, reason);
    persist(order, lines);
  }

  @Transactional(rollbackFor = Exception.class)
  public void reattribute(Long id) {
    var order = locked(id);
    var lines = lines(id);
    attribution.apply(order, lines, true);
    persist(order, lines);
  }

  private OrderDO locked(Long id) {
    var row =
        orders.selectOne(
            new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getId, id).last("FOR UPDATE"));
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var user = current.user();
    if (user.ownScope() && !Objects.equals(row.getBdUserId(), user.id())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return row;
  }

  private List<OrderItemDO> lines(Long id) {
    return items.selectList(new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, id));
  }

  private void persist(OrderDO order, List<OrderItemDO> lines) {
    orders.updateById(order);
    lines.forEach(items::updateById);
  }

  private static OrderItemDTO line(OrderItemDO i, Map<Long, KolRefDTO> kolById) {
    var kol = i.getKolId() == null ? null : kolById.get(i.getKolId());
    return new OrderItemDTO(
        i.getId(),
        i.getSubOrderId(),
        i.getWeidianItemId(),
        i.getSkuId(),
        i.getItemName(),
        i.getSkuName(),
        i.getQuantity(),
        i.getPayAmount(),
        i.getRefundAmount(),
        i.getKolId(),
        kol == null ? null : kol.kolCode(),
        i.getProductId(),
        i.getKolItemId(),
        i.getAttributionType(),
        i.getManualReason());
  }
}
