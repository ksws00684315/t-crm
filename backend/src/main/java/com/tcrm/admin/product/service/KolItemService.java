package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRateDTO;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemSalesQuery;
import com.tcrm.admin.product.api.KolItemSalesTotal;
import com.tcrm.admin.product.api.KolItemVisitQuery;
import com.tcrm.admin.product.api.KolItemVisitTotal;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolCleanupLatestRow;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Creator-item page (task 39). OWN-scope staff see mappings of their assigned KOLs. An empty filter
 * lists the company. Sales are loaded once for the current page. Visits are loaded only for the
 * detail.
 */
@Service
public class KolItemService {
  private final KolItemMapper items;
  private final ProductMapper products;
  private final ShopMapper shops;
  private final KolCleanupItemMapper cleanups;
  private final KolApi kols;
  private final KolItemSalesQuery sales;
  private final KolItemVisitQuery visits;
  private final CurrentUserContext current;

  public KolItemService(
      KolItemMapper items,
      ProductMapper products,
      ShopMapper shops,
      KolCleanupItemMapper cleanups,
      KolApi kols,
      KolItemSalesQuery sales,
      KolItemVisitQuery visits,
      CurrentUserContext current) {
    this.items = items;
    this.products = products;
    this.shops = shops;
    this.cleanups = cleanups;
    this.kols = kols;
    this.sales = sales;
    this.visits = visits;
    this.current = current;
  }

  public PageResult<KolItemView> page(KolItemFilterDTO filter) {
    AccountDTO user = current.user();
    List<Long> kolIds = visibleKolIds(user, filter.kolKeyword());
    if (kolIds != null && kolIds.isEmpty()) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    String linkCode = filter.linkCode() == null ? null : filter.linkCode().toUpperCase(Locale.ROOT);
    var query =
        new LambdaQueryWrapper<KolItemDO>()
            .eq(linkCode != null, KolItemDO::getLinkCode, linkCode)
            .in(kolIds != null, KolItemDO::getKolId, kolIds == null ? List.of() : kolIds)
            .eq(filter.shopId() != null, KolItemDO::getShopId, filter.shopId())
            .orderByDesc(KolItemDO::getCreateTime)
            .orderByDesc(KolItemDO::getId);
    var result = items.selectPage(new Page<>(filter.page(), filter.size()), query);
    List<KolItemDO> rows = result.getRecords();
    if (rows.isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), filter.page(), filter.size());
    }
    Instant now = Instant.now();
    Context ctx = load(rows, false);
    return new PageResult<>(
        rows.stream().map(row -> toView(row, ctx, now)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  public KolItemDetail detail(long id) {
    AccountDTO user = current.user();
    KolItemDO row = items.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (!visible(user, row.getKolId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    Instant now = Instant.now();
    Context ctx = load(List.of(row), true);
    KolItemView view = toView(row, ctx, now);
    KolItemSalesTotal sale = ctx.sales().get(row.getId());
    KolCleanupLatestRow cleanup = ctx.cleanups().get(row.getId());
    return new KolItemDetail(
        view,
        WeidianItemUrls.of(view.sourceItemId()),
        ctx.visits().getOrDefault(row.getId(), 0L),
        view.payAmount(),
        KolItemStatus.money(sale == null ? null : sale.refundAmount()),
        cleanup == null ? null : cleanup.getCleanupTime(),
        cleanup == null ? null : cleanup.getReason(),
        cleanup == null ? null : cleanup.getFailReason());
  }

  /**
   * Null means no KOL restriction. An empty list means the page is empty and must not be queried.
   */
  private List<Long> visibleKolIds(AccountDTO user, String keyword) {
    List<Long> scoped = null;
    if (user.ownScope()) {
      scoped = kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).toList();
      if (scoped.isEmpty()) {
        return List.of();
      }
    }
    if (keyword == null) {
      return scoped;
    }
    List<Long> matched = kols.findIdsByKeyword(keyword);
    if (matched.isEmpty()) {
      return List.of();
    }
    if (scoped == null) {
      return matched;
    }
    Set<Long> allow = new HashSet<>(scoped);
    return matched.stream().filter(allow::contains).toList();
  }

  private boolean visible(AccountDTO user, Long kolId) {
    if (!user.ownScope()) {
      return true;
    }
    return kols.listByBdUserId(user.id()).stream().anyMatch(kol -> kol.id().equals(kolId));
  }

  private Context load(List<KolItemDO> rows, boolean withVisits) {
    List<Long> ids = rows.stream().map(KolItemDO::getId).toList();
    Map<Long, KolItemSalesTotal> saleMap = new HashMap<>();
    for (KolItemSalesTotal row : sales.sumByKolItemIds(ids)) {
      saleMap.put(row.kolItemId(), row);
    }
    Map<Long, Long> visitMap = new HashMap<>();
    if (withVisits) {
      for (KolItemVisitTotal row : visits.countByKolItemIds(ids)) {
        visitMap.put(row.kolItemId(), row.visitCount());
      }
    }
    Map<Long, KolCleanupLatestRow> cleanupMap = new HashMap<>();
    for (KolCleanupLatestRow row : cleanups.listByKolItemIds(ids)) {
      cleanupMap.putIfAbsent(row.getKolItemId(), row);
    }
    return new Context(
        kolMap(rows),
        productMap(rows),
        shopMap(rows),
        rateMap(rows),
        saleMap,
        visitMap,
        cleanupMap);
  }

  private Map<Long, KolRefDTO> kolMap(List<KolItemDO> rows) {
    Set<Long> wanted = new HashSet<>();
    rows.stream().map(KolItemDO::getKolId).filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, KolRefDTO> map = new HashMap<>();
    for (KolRefDTO kol : kols.listByIds(wanted)) {
      map.put(kol.id(), kol);
    }
    return map;
  }

  private Map<Long, ProductDO> productMap(List<KolItemDO> rows) {
    Set<Long> wanted = new HashSet<>();
    rows.stream().map(KolItemDO::getProductId).filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, ProductDO> map = new HashMap<>();
    if (wanted.isEmpty()) {
      return map;
    }
    for (ProductDO product :
        products.selectList(new LambdaQueryWrapper<ProductDO>().in(ProductDO::getId, wanted))) {
      map.put(product.getId(), product);
    }
    return map;
  }

  private Map<Long, ShopDO> shopMap(List<KolItemDO> rows) {
    Set<Long> wanted = new HashSet<>();
    rows.stream().map(KolItemDO::getShopId).filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, ShopDO> map = new HashMap<>();
    if (wanted.isEmpty()) {
      return map;
    }
    for (ShopDO shop :
        shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, wanted))) {
      map.put(shop.getId(), shop);
    }
    return map;
  }

  private Map<Long, String> rateMap(List<KolItemDO> rows) {
    Set<Long> wanted = new HashSet<>();
    rows.stream().map(KolItemDO::getKolId).filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, String> map = new HashMap<>();
    for (KolRateDTO rate : kols.listRatesByIds(wanted)) {
      map.put(rate.kolId(), rate.rateText());
    }
    return map;
  }

  private static KolItemView toView(KolItemDO row, Context ctx, Instant now) {
    KolRefDTO kol = ctx.kols().get(row.getKolId());
    ProductDO product = ctx.products().get(row.getProductId());
    ShopDO shop = ctx.shops().get(row.getShopId());
    KolItemSalesTotal sale = ctx.sales().get(row.getId());
    KolCleanupLatestRow cleanup = ctx.cleanups().get(row.getId());
    String sourceItemId = product == null ? null : product.getWeidianItemId();
    String image = product == null ? null : blankToNull(product.getImageUrl());
    String name =
        product != null && !blank(product.getName()) ? product.getName() : row.getItemName();
    return new KolItemView(
        row.getId(),
        row.getLinkCode(),
        name,
        image,
        row.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        row.getShopId(),
        shop == null ? null : shop.getShopName(),
        shop == null ? null : shop.getShopCode(),
        KolItemStatus.CHANNEL,
        row.getWeidianItemId(),
        WeidianItemUrls.of(row.getWeidianItemId()),
        sourceItemId,
        product == null ? null : KolItemStatus.CHANNEL,
        ctx.rates().get(row.getKolId()),
        sale == null ? 0L : sale.salesQuantity(),
        KolItemStatus.money(sale == null ? null : sale.payAmount()),
        KolItemStatus.mapping(now, row.getValidFrom(), row.getValidTo()),
        KolItemStatus.cleanup(cleanup == null ? null : cleanup.getStatus()),
        row.getCreateTime());
  }

  private static String blankToNull(String value) {
    return blank(value) ? null : value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private record Context(
      Map<Long, KolRefDTO> kols,
      Map<Long, ProductDO> products,
      Map<Long, ShopDO> shops,
      Map<Long, String> rates,
      Map<Long, KolItemSalesTotal> sales,
      Map<Long, Long> visits,
      Map<Long, KolCleanupLatestRow> cleanups) {}
}
