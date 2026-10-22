package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Refund list and detail queries (task 40 §3.3), tenant-filtered with payment-time OWN scope. */
@Service
public class RefundQueryService {
  private final RefundMapper refunds;
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final ShopAccessApi shops;
  private final KolApi kols;
  private final ProductAccessApi products;
  private final CurrentUserContext current;

  public RefundQueryService(
      RefundMapper refunds,
      OrderMapper orders,
      OrderItemMapper items,
      ShopAccessApi shops,
      KolApi kols,
      ProductAccessApi products,
      CurrentUserContext current) {
    this.refunds = refunds;
    this.orders = orders;
    this.items = items;
    this.shops = shops;
    this.kols = kols;
    this.products = products;
    this.current = current;
  }

  /** Paginated refunds; an unknown channel filter selects nothing (Weidian is the only channel). */
  public PageResult<RefundDTO> page(RefundFilterDTO filter) {
    var user = current.user();
    if (!OrderChannels.known(filter.channel())) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    var query =
        new LambdaQueryWrapper<RefundDO>()
            .eq(
                filter.refundNo() != null && !filter.refundNo().isBlank(),
                RefundDO::getWeidianRefundNo,
                filter.refundNo())
            .eq(filter.refundStatus() != null, RefundDO::getRefundStatus, filter.refundStatus())
            .eq(filter.refundType() != null, RefundDO::getRefundType, filter.refundType());
    if (filter.orderNo() != null && !filter.orderNo().isBlank()) {
      var ids =
          orderIds(
              new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getWeidianOrderId, filter.orderNo()));
      if (ids.isEmpty()) {
        return new PageResult<>(List.of(), 0, filter.page(), filter.size());
      }
      query.in(RefundDO::getOrderId, ids);
    }
    if (user.ownScope()) {
      // Unattributed orders (bd_user_id null) are invisible to BD accounts by this predicate.
      query.exists(
          "SELECT 1 FROM crm_order o WHERE o.id = crm_refund.order_id AND o.bd_user_id = {0}",
          user.id());
    }
    query.orderByDesc(RefundDO::getRefundTime, RefundDO::getId);
    var result = refunds.selectPage(new Page<>(filter.page(), filter.size()), query);
    if (result.getRecords().isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), filter.page(), filter.size());
    }
    var rows = decorate(result.getRecords());
    return new PageResult<>(rows, result.getTotal(), filter.page(), filter.size());
  }

  /** Refund detail with its single linked order line (task 40 §3.3). */
  public RefundDetailDTO detail(Long id) {
    var refund = refunds.selectById(id);
    if (refund == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var order = scopedOrder(refund.getOrderId());
    var shop = order.getShopId() == null ? null : shops.findShop(order.getShopId());
    var line = refund.getOrderItemId() == null ? null : items.selectById(refund.getOrderItemId());
    Set<Long> kolIds = new HashSet<>();
    if (order.getKolId() != null) {
      kolIds.add(order.getKolId());
    }
    if (line != null && line.getKolId() != null) {
      kolIds.add(line.getKolId());
    }
    Map<Long, KolRefDTO> kolById =
        kols.listByIds(kolIds).stream()
            .collect(Collectors.toMap(KolRefDTO::id, Function.identity()));
    var orderKol = order.getKolId() == null ? null : kolById.get(order.getKolId());
    var lineKol = line == null || line.getKolId() == null ? null : kolById.get(line.getKolId());
    RefundDetailDTO.RefundLineDTO lineDto = null;
    if (line != null) {
      var product = line.getProductId() == null ? null : products.getProduct(line.getProductId());
      lineDto =
          new RefundDetailDTO.RefundLineDTO(
              line.getId(),
              product == null ? null : product.imageUrl(),
              line.getItemName(),
              line.getWeidianItemId(),
              line.getSkuName(),
              line.getKolId(),
              lineKol == null ? null : lineKol.kolCode(),
              lineKol == null ? null : lineKol.name(),
              line.getQuantity(),
              // This refund's own amount on the line; the line's total lives in the order detail.
              refund.getAmount());
    }
    Long kolId = line != null && line.getKolId() != null ? line.getKolId() : order.getKolId();
    var kol = kolId == null ? null : kolById.get(kolId);
    return new RefundDetailDTO(
        refund.getId(),
        refund.getWeidianRefundNo(),
        order.getId(),
        order.getWeidianOrderId(),
        OrderChannels.WEIDIAN,
        order.getShopId(),
        shop == null ? null : shop.name(),
        shop == null ? null : shop.shopCode(),
        shop == null ? null : shop.weidianShopId(),
        kolId,
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        refund.getRefundType(),
        refund.getRefundStatus(),
        refund.getAmount(),
        refund.getRefundTime(),
        refund.getFinishTime(),
        lineDto == null ? List.of() : List.of(lineDto));
  }

  private List<RefundDTO> decorate(List<RefundDO> records) {
    Map<Long, OrderDO> orderById =
        orders.selectByIds(records.stream().map(RefundDO::getOrderId).distinct().toList()).stream()
            .collect(Collectors.toMap(OrderDO::getId, Function.identity()));
    var lineIds =
        records.stream().map(RefundDO::getOrderItemId).filter(Objects::nonNull).distinct().toList();
    Map<Long, OrderItemDO> lineById =
        lineIds.isEmpty()
            ? Map.of()
            : items.selectByIds(lineIds).stream()
                .collect(Collectors.toMap(OrderItemDO::getId, Function.identity()));
    Map<Long, ShopAccessDTO> shopById =
        shops.listShops().stream()
            .collect(Collectors.toMap(ShopAccessDTO::id, Function.identity(), (a, b) -> a));
    Set<Long> kolIds = new HashSet<>();
    for (var refund : records) {
      var order = orderById.get(refund.getOrderId());
      if (order != null && order.getKolId() != null) {
        kolIds.add(order.getKolId());
      }
      var line = refund.getOrderItemId() == null ? null : lineById.get(refund.getOrderItemId());
      if (line != null && line.getKolId() != null) {
        kolIds.add(line.getKolId());
      }
    }
    Map<Long, KolRefDTO> kolById =
        kols.listByIds(kolIds).stream()
            .collect(Collectors.toMap(KolRefDTO::id, Function.identity()));
    return records.stream()
        .map(
            refund -> {
              var order = orderById.get(refund.getOrderId());
              var line =
                  refund.getOrderItemId() == null ? null : lineById.get(refund.getOrderItemId());
              Long kolId =
                  line != null && line.getKolId() != null
                      ? line.getKolId()
                      : order == null ? null : order.getKolId();
              var kol = kolId == null ? null : kolById.get(kolId);
              var shop =
                  order == null || order.getShopId() == null
                      ? null
                      : shopById.get(order.getShopId());
              return new RefundDTO(
                  refund.getId(),
                  refund.getWeidianRefundNo(),
                  refund.getOrderId(),
                  order == null ? null : order.getWeidianOrderId(),
                  OrderChannels.WEIDIAN,
                  order == null ? null : order.getShopId(),
                  shop == null ? null : shop.name(),
                  shop == null ? null : shop.shopCode(),
                  kolId,
                  kol == null ? null : kol.kolCode(),
                  kol == null ? null : kol.name(),
                  refund.getRefundType(),
                  refund.getRefundStatus(),
                  refund.getAmount(),
                  refund.getRefundTime(),
                  refund.getFinishTime());
            })
        .toList();
  }

  private List<Long> orderIds(LambdaQueryWrapper<OrderDO> wrapper) {
    return orders.selectList(wrapper.select(OrderDO::getId)).stream().map(OrderDO::getId).toList();
  }

  private OrderDO scopedOrder(Long orderId) {
    var order = orders.selectById(orderId);
    if (order == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var user = current.user();
    if (user.ownScope() && !Objects.equals(order.getBdUserId(), user.id())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return order;
  }
}
