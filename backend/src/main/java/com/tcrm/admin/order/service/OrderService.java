package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.CopyJobLookupApi;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkCodeDTO;
import com.tcrm.admin.product.api.KolMappingEvidenceDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
  /** Recent failures the monitoring list returns (task 40 §3.5); newest first. */
  static final int FAILURE_FEED_LIMIT = 100;

  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final RefundMapper refunds;
  private final OrderSyncStateMapper syncStates;
  private final OrderSyncFailureMapper syncFailures;
  private final KolApi kols;
  private final ShopAccessApi shops;
  private final BdUserApi bdUsers;
  private final WeidianAuthorizationApi authorization;
  private final OrderAttribution attribution;
  private final CurrentUserContext current;
  private final KolItemApi kolItems;
  private final ProductAccessApi products;
  private final CopyJobLookupApi copyJobs;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;

  public OrderService(
      OrderMapper orders,
      OrderItemMapper items,
      RefundMapper refunds,
      OrderSyncStateMapper syncStates,
      OrderSyncFailureMapper syncFailures,
      KolApi kols,
      ShopAccessApi shops,
      BdUserApi bdUsers,
      WeidianAuthorizationApi authorization,
      OrderAttribution attribution,
      CurrentUserContext current,
      KolItemApi kolItems,
      ProductAccessApi products,
      CopyJobLookupApi copyJobs,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer) {
    this.orders = orders;
    this.items = items;
    this.refunds = refunds;
    this.syncStates = syncStates;
    this.syncFailures = syncFailures;
    this.kols = kols;
    this.shops = shops;
    this.bdUsers = bdUsers;
    this.authorization = authorization;
    this.attribution = attribution;
    this.current = current;
    this.kolItems = kolItems;
    this.products = products;
    this.copyJobs = copyJobs;
    this.audit = audit;
    this.sanitizer = sanitizer;
  }

  public PageResult<OrderDTO> page(OrderFilterDTO filter, boolean unmatched) {
    var user = current.user();
    if (!OrderChannels.known(filter.channel())) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    var query =
        new LambdaQueryWrapper<OrderDO>()
            .eq(filter.shopId() != null, OrderDO::getShopId, filter.shopId())
            .eq(
                filter.orderNo() != null && !filter.orderNo().isBlank(),
                OrderDO::getWeidianOrderId,
                filter.orderNo())
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
    Map<Long, List<RefundDO>> refundRows =
        refunds
            .selectList(new LambdaQueryWrapper<RefundDO>().in(RefundDO::getOrderId, ids))
            .stream()
            .collect(Collectors.groupingBy(RefundDO::getOrderId));
    Map<Long, BigDecimal> totals = new HashMap<>();
    refundRows.forEach(
        (orderId, rows) ->
            totals.put(
                orderId,
                rows.stream()
                    .filter(r -> "SUCCESS".equals(r.getRefundStatus()))
                    .map(RefundDO::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)));
    return new PageResult<>(
        decorate(result.getRecords(), lines, refundRows, totals),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  /** Fills display names per page with a fixed number of batch lookups (task 19a §4.1.1). */
  private List<OrderDTO> decorate(
      List<OrderDO> records,
      Map<Long, List<OrderItemDO>> lines,
      Map<Long, List<RefundDO>> refundRows,
      Map<Long, BigDecimal> totals) {
    Map<Long, ShopAccessDTO> shopById =
        shops.listShops().stream()
            .collect(Collectors.toMap(ShopAccessDTO::id, Function.identity(), (a, b) -> a));
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
              var shop = o.getShopId() == null ? null : shopById.get(o.getShopId());
              return new OrderDTO(
                  o.getId(),
                  o.getShopId(),
                  shop == null ? null : shop.name(),
                  shop == null ? null : shop.shopCode(),
                  OrderChannels.WEIDIAN,
                  o.getWeidianOrderId(),
                  o.getWeidianOrderId(),
                  o.getKolId(),
                  kol == null ? null : kol.kolCode(),
                  kol == null ? null : kol.name(),
                  o.getBdUserId(),
                  displayName(bdById.get(o.getBdUserId())),
                  o.getOrderStatus(),
                  OrderRefundStatuses.of(refundRows.get(o.getId()), o.getPayAmount()),
                  o.getAttributionStatus(),
                  o.getOrderTime(),
                  o.getPayTime(),
                  o.getCreateTime(),
                  o.getTotalAmount(),
                  o.getPayAmount(),
                  o.getFreight(),
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

  /** Order detail (task 40 §3.2): amounts, times, provenance and every product line. */
  public OrderDetailDTO detail(Long id) {
    var order = readable(id);
    return detail(order, lines(id), refundRows(id));
  }

  private OrderDetailDTO detail(OrderDO order, List<OrderItemDO> lines, List<RefundDO> refunds) {
    var shop = order.getShopId() == null ? null : shops.findShop(order.getShopId());
    var linesKols = kolNames(lines);
    var images = productImages(lines);
    var itemAmount =
        lines.stream()
            .map(OrderItemDO::getPayAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    var orderKol =
        order.getKolId() == null ? null : kolById(List.of(order.getKolId())).get(order.getKolId());
    return new OrderDetailDTO(
        order.getId(),
        order.getWeidianOrderId(),
        OrderChannels.WEIDIAN,
        order.getShopId(),
        shop == null ? null : shop.name(),
        shop == null ? null : shop.shopCode(),
        shop == null ? null : shop.weidianShopId(),
        order.getKolId(),
        orderKol == null ? null : orderKol.kolCode(),
        orderKol == null ? null : orderKol.name(),
        order.getOrderStatus(),
        OrderRefundStatuses.of(refunds, order.getPayAmount()),
        order.getAttributionStatus(),
        sourceName(order.getSource()),
        order.getOrderTime(),
        order.getPayTime(),
        order.getLastSyncTime(),
        order.getCreateTime(),
        itemAmount,
        order.getTotalAmount(),
        order.getPayAmount(),
        order.getFreight(),
        lines.stream()
            .map(
                line -> {
                  var kol = line.getKolId() == null ? null : linesKols.get(line.getKolId());
                  return new OrderDetailDTO.OrderDetailItemDTO(
                      line.getId(),
                      images.get(line.getProductId()),
                      line.getItemName(),
                      line.getWeidianItemId(),
                      line.getSkuName(),
                      line.getKolId(),
                      kol == null ? null : kol.kolCode(),
                      kol == null ? null : kol.name(),
                      line.getQuantity(),
                      unitPrice(line),
                      line.getPayAmount(),
                      line.getRefundAmount(),
                      order.getOrderStatus());
                })
            .toList());
  }

  /**
   * Unit price derived from the line: pay amount split over the quantity, half-up at 2 decimals.
   */
  static BigDecimal unitPrice(OrderItemDO line) {
    if (line.getPayAmount() == null || line.getQuantity() == null || line.getQuantity() <= 0) {
      return null;
    }
    return line.getPayAmount()
        .divide(BigDecimal.valueOf(line.getQuantity()), 2, RoundingMode.HALF_UP);
  }

  static String sourceName(Integer source) {
    if (source == null) {
      return null;
    }
    return switch (source) {
      case 1 -> "API";
      case 2 -> "IMPORT";
      default -> null;
    };
  }

  /** Order chain (task 40 §3.4): per line the copy job, link, amounts and refunds. */
  public OrderChainDTO chain(Long id) {
    var order = readable(id);
    var lines = lines(id);
    var refunds = refundRows(id);
    Set<Long> kolItemIds =
        lines.stream()
            .map(OrderItemDO::getKolItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, KolMappingEvidenceDTO> mappingById =
        kolItems.listMappingsByIds(kolItemIds).stream()
            .collect(Collectors.toMap(KolMappingEvidenceDTO::kolItemId, Function.identity()));
    Map<Long, String> linkCodeById =
        kolItems.listLinkCodesByIds(kolItemIds).stream()
            .collect(
                Collectors.toMap(
                    KolItemLinkCodeDTO::kolItemId, KolItemLinkCodeDTO::linkCode, (a, b) -> a));
    Set<Long> kolIds = new HashSet<>();
    if (order.getKolId() != null) {
      kolIds.add(order.getKolId());
    }
    for (var line : lines) {
      if (line.getKolId() != null) {
        kolIds.add(line.getKolId());
      }
    }
    Map<Long, KolRefDTO> kolById = kolById(kolIds);
    var chainLines = new ArrayList<OrderChainDTO.ChainLineDTO>();
    for (var line : lines) {
      var mapping = line.getKolItemId() == null ? null : mappingById.get(line.getKolItemId());
      OrderChainDTO.ChainLinkDTO link = null;
      OrderChainDTO.ChainCopyJobDTO job = null;
      if (mapping != null) {
        var kol = mapping.kolId() == null ? null : kolById.get(mapping.kolId());
        link =
            new OrderChainDTO.ChainLinkDTO(
                mapping.kolItemId(),
                linkCodeById.get(mapping.kolItemId()),
                mapping.productId(),
                mapping.weidianItemId(),
                mapping.kolId(),
                kol == null ? null : kol.kolCode(),
                kol == null ? null : kol.name());
        var copied = copyJobs.findByTargetItemId(mapping.weidianItemId());
        job =
            copied == null
                ? null
                : new OrderChainDTO.ChainCopyJobDTO(
                    copied.jobCode(), copied.createTime(), copied.status());
      }
      chainLines.add(
          new OrderChainDTO.ChainLineDTO(
              line.getId(),
              line.getSubOrderId(),
              job,
              link,
              line.getQuantity(),
              line.getPayAmount(),
              refunds.stream()
                  .filter(r -> Objects.equals(r.getOrderItemId(), line.getId()))
                  .map(
                      r ->
                          new OrderChainDTO.ChainRefundDTO(
                              r.getId(),
                              r.getWeidianRefundNo(),
                              r.getRefundType(),
                              r.getRefundStatus(),
                              r.getAmount(),
                              r.getFinishTime()))
                  .toList(),
              null,
              null));
    }
    return new OrderChainDTO(order.getId(), order.getWeidianOrderId(), chainLines);
  }

  /** Match evidence of one order line in the manual pool (task 40 §3.6). */
  public UnmatchedEvidenceDTO evidence(Long orderItemId) {
    var line =
        items.selectOne(new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getId, orderItemId));
    if (line == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var order = readable(line.getOrderId());
    var shop = order.getShopId() == null ? null : shops.findShop(order.getShopId());
    var candidates = kolItems.listMappingsByShopItem(order.getShopId(), line.getWeidianItemId());
    Set<Long> kolIds =
        candidates.stream()
            .map(KolMappingEvidenceDTO::kolId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, KolRefDTO> kolById = kolById(kolIds);
    var productRef = productRef(candidates);
    var mappings = new ArrayList<UnmatchedEvidenceDTO.MappingDTO>();
    Map<Long, String> evidenceLinkCodes =
        kolItems
            .listLinkCodesByIds(
                candidates.stream()
                    .map(KolMappingEvidenceDTO::kolItemId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(
                Collectors.toMap(
                    KolItemLinkCodeDTO::kolItemId, KolItemLinkCodeDTO::linkCode, (a, b) -> a));
    for (var mapping : candidates) {
      var kol = mapping.kolId() == null ? null : kolById.get(mapping.kolId());
      mappings.add(
          new UnmatchedEvidenceDTO.MappingDTO(
              mapping.kolItemId(),
              mapping.kolId(),
              kol == null ? null : kol.kolCode(),
              kol == null ? null : kol.name(),
              evidenceLinkCodes.get(mapping.kolItemId()),
              mapping.validFrom(),
              mapping.validTo(),
              covers(mapping, order.getPayTime())));
    }
    boolean anyCover =
        order.getPayTime() != null
            && candidates.stream().anyMatch(m -> covers(m, order.getPayTime()));
    return new UnmatchedEvidenceDTO(
        line.getId(),
        order.getId(),
        order.getWeidianOrderId(),
        order.getShopId(),
        shop == null ? null : shop.name(),
        shop == null ? null : shop.weidianShopId(),
        line.getWeidianItemId(),
        line.getItemName(),
        line.getSkuName(),
        order.getPayTime(),
        order.getAttributionStatus(),
        line.getAttributionType() == null ? null : String.valueOf(line.getAttributionType()),
        unmatchedReason(order, candidates, anyCover),
        productRef,
        mappings,
        new UnmatchedEvidenceDTO.ManualActionDTO(
            line.getAttributionType(), line.getManualReason(), line.getAttributionTime()));
  }

  /**
   * Reason codes of the manual pool (row 10.01): PENDING 未支付, CONFLICT 证据冲突, NO_MAPPING 缺规则
   * (专属商品未登记), MAPPING_EXPIRED 映射在支付时已失效, MATCHED 其他（已匹配或人工处理）.
   */
  static String unmatchedReason(
      OrderDO order, List<KolMappingEvidenceDTO> candidates, boolean anyCover) {
    if ("PENDING".equals(order.getAttributionStatus()) && order.getPayTime() == null) {
      return "PENDING";
    }
    if ("CONFLICT".equals(order.getAttributionStatus())) {
      return "CONFLICT";
    }
    if (candidates.isEmpty()) {
      return "NO_MAPPING";
    }
    if (order.getPayTime() != null && !anyCover) {
      return "MAPPING_EXPIRED";
    }
    return "MATCHED";
  }

  private static boolean covers(KolMappingEvidenceDTO mapping, Instant payTime) {
    if (payTime == null) {
      return false;
    }
    if (mapping.validFrom() != null && payTime.isBefore(mapping.validFrom())) {
      return false;
    }
    return mapping.validTo() == null || payTime.isBefore(mapping.validTo());
  }

  private UnmatchedEvidenceDTO.ProductRef productRef(List<KolMappingEvidenceDTO> candidates) {
    var productIds =
        candidates.stream()
            .map(KolMappingEvidenceDTO::productId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (productIds.isEmpty()) {
      return null;
    }
    var product = products.getProduct(productIds.getFirst());
    return product == null
        ? null
        : new UnmatchedEvidenceDTO.ProductRef(product.id(), product.code(), product.name());
  }

  /** Every shop of the current company with its sync status, including never-synced ones. */
  public List<OrderSyncStateDTO> listSyncStates() {
    var authorized = authorization.authorizedShopIds();
    Map<Long, OrderSyncStateDO> states =
        syncStates.selectList(new LambdaQueryWrapper<OrderSyncStateDO>()).stream()
            .collect(Collectors.toMap(OrderSyncStateDO::getShopId, Function.identity()));
    Map<Long, OrderMapper.ShopQualityRow> orderQuality =
        orders.missingOrderFields().stream()
            .collect(Collectors.toMap(OrderMapper.ShopQualityRow::getShopId, Function.identity()));
    Map<Long, OrderItemMapper.ItemQualityRow> itemQuality =
        items.missingItemFields().stream()
            .collect(
                Collectors.toMap(OrderItemMapper.ItemQualityRow::getShopId, Function.identity()));
    Map<Long, RefundMapper.RefundQualityRow> refundQuality =
        refunds.missingRefundFields().stream()
            .collect(
                Collectors.toMap(RefundMapper.RefundQualityRow::getShopId, Function.identity()));
    Map<Long, OrderMapper.ShopSourceRow> orderSource =
        orders.latestOrderSourcePerShop().stream()
            .collect(Collectors.toMap(OrderMapper.ShopSourceRow::getShopId, Function.identity()));
    Map<Long, RefundMapper.RefundSourceRow> refundSource =
        refunds.latestRefundSourcePerShop().stream()
            .collect(
                Collectors.toMap(RefundMapper.RefundSourceRow::getShopId, Function.identity()));
    Map<String, Long> failures24h = failureCountsSince(Instant.now().minus(Duration.ofHours(24)));
    return shops.listShops().stream()
        .map(
            shop -> {
              var state = states.get(shop.id());
              var orderRow = orderQuality.get(shop.id());
              var itemRow = itemQuality.get(shop.id());
              var refundRow = refundQuality.get(shop.id());
              var orderSourceRow = orderSource.get(shop.id());
              var refundSourceRow = refundSource.get(shop.id());
              var orderFields = new LinkedHashMap<String, Long>();
              orderFields.put(
                  "payTime", count(orderRow == null ? null : orderRow.getMissingPayTime()));
              orderFields.put(
                  "orderTime", count(orderRow == null ? null : orderRow.getMissingOrderTime()));
              orderFields.put(
                  "payAmount", count(orderRow == null ? null : orderRow.getMissingPayAmount()));
              orderFields.put(
                  "totalAmount", count(orderRow == null ? null : orderRow.getMissingTotalAmount()));
              orderFields.put(
                  "itemItemId", count(itemRow == null ? null : itemRow.getMissingItemId()));
              orderFields.put(
                  "itemPayAmount", count(itemRow == null ? null : itemRow.getMissingPayAmount()));
              var refundFields = new LinkedHashMap<String, Long>();
              refundFields.put(
                  "orderItemId",
                  count(refundRow == null ? null : refundRow.getMissingOrderItemId()));
              refundFields.put(
                  "finishTime", count(refundRow == null ? null : refundRow.getMissingFinishTime()));
              return new OrderSyncStateDTO(
                  shop.id(),
                  shop.name(),
                  Integer.valueOf(1).equals(shop.status()),
                  authorized.contains(shop.id()),
                  state == null ? null : state.getLastSuccessAt(),
                  state == null ? null : state.getLastError(),
                  new OrderSyncStateDTO.KindSyncDTO(
                      orderSourceRow == null ? null : sourceName(orderSourceRow.getLatestSource()),
                      state == null ? null : state.getLastSuccessAt(),
                      state == null ? null : state.getLastError(),
                      failures24h.getOrDefault(shop.id() + ":ORDER_SYNC", 0L),
                      orderFields),
                  new OrderSyncStateDTO.KindSyncDTO(
                      refundSourceRow == null
                          ? null
                          : sourceName(refundSourceRow.getLatestSource()),
                      state == null ? null : state.getLastSuccessAt(),
                      state == null ? null : state.getLastError(),
                      failures24h.getOrDefault(shop.id() + ":REFUND_SYNC", 0L),
                      refundFields),
                  state == null || state.getLastImportCutoffAt() == null
                      ? null
                      : new OrderSyncStateDTO.ImportCutoffDTO(
                          state.getLastImportKind(),
                          state.getLastImportCutoffAt(),
                          state.getLastImportTaskId()));
            })
        .toList();
  }

  private static long count(Long value) {
    return value == null ? 0L : value;
  }

  /** Failure counts of the last 24 hours keyed by {@code shopId:kind}. */
  private Map<String, Long> failureCountsSince(Instant since) {
    return syncFailures
        .selectList(
            new LambdaQueryWrapper<OrderSyncFailureDO>()
                .ge(OrderSyncFailureDO::getCreateTime, since))
        .stream()
        .collect(
            Collectors.groupingBy(
                row -> row.getShopId() + ":" + row.getKind(), Collectors.counting()));
  }

  /**
   * Deletes this company's sync failures with create_time strictly before now − olderThanDays.
   * Returns the number of deleted rows (task 40 cleanup).
   */
  @Transactional(rollbackFor = Exception.class)
  public int deleteSyncFailures(int olderThanDays) {
    if (olderThanDays < 1 || olderThanDays > 3650) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Instant cutoff = Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
    int total = 0;
    int deleted;
    do {
      deleted = syncFailures.deleteOlderThan(cutoff, 10_000);
      total += deleted;
    } while (deleted >= 10_000);
    var user = current.user();
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "ORDER",
        "Clean order sync failures olderThanDays=" + olderThanDays + " deleted=" + total,
        "DELETE",
        "/admin-api/order/sync-failures",
        sanitizer.sanitize(Map.of("olderThanDays", olderThanDays, "deleted", total)));
    return total;
  }

  /** Recent sync and import failures of the company, newest first (task 40 §3.5). */
  public List<SyncFailureDTO> listSyncFailures() {
    Map<Long, String> shopNames =
        shops.listShops().stream()
            .collect(Collectors.toMap(ShopAccessDTO::id, ShopAccessDTO::name, (a, b) -> a));
    return syncFailures
        .selectList(
            new LambdaQueryWrapper<OrderSyncFailureDO>()
                .orderByDesc(OrderSyncFailureDO::getId)
                .last("LIMIT " + FAILURE_FEED_LIMIT))
        .stream()
        .map(
            row ->
                new SyncFailureDTO(
                    row.getId(),
                    row.getShopId(),
                    row.getShopId() == null ? null : shopNames.get(row.getShopId()),
                    row.getKind(),
                    row.getError(),
                    row.getTaskId(),
                    row.getCreateTime()))
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

  /** Read path of {@link #locked(Long)}: same tenant and OWN rules, no row lock. */
  private OrderDO readable(Long id) {
    var row = orders.selectById(id);
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

  private List<RefundDO> refundRows(Long orderId) {
    return refunds.selectList(new LambdaQueryWrapper<RefundDO>().eq(RefundDO::getOrderId, orderId));
  }

  private void persist(OrderDO order, List<OrderItemDO> lines) {
    orders.updateById(order);
    lines.forEach(items::updateById);
  }

  private Map<Long, KolRefDTO> kolById(java.util.Collection<Long> kolIds) {
    return kols.listByIds(kolIds).stream()
        .collect(Collectors.toMap(KolRefDTO::id, Function.identity()));
  }

  private Map<Long, KolRefDTO> kolNames(List<OrderItemDO> lines) {
    Set<Long> kolIds =
        lines.stream()
            .map(OrderItemDO::getKolId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return kolById(kolIds);
  }

  /** Main image per original-product id of the lines; missing products and images map to null. */
  private Map<Long, String> productImages(List<OrderItemDO> lines) {
    Set<Long> productIds =
        lines.stream()
            .map(OrderItemDO::getProductId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> images = new HashMap<>();
    for (var product : products.listProducts(productIds)) {
      images.put(product.id(), product.imageUrl());
    }
    return images;
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
