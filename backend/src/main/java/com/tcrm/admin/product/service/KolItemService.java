package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Kol item mapping query (task 05 §4.3, §4.4). OWN-scope staff are limited to their assigned KOLs.
 * Other scopes require at least one of kolCode / productCode / weidianItemId to avoid full-table
 * COUNT at design capacity.
 */
@Service
public class KolItemService {
  private final KolItemMapper items;
  private final ProductMapper products;
  private final ShopMapper shops;
  private final KolApi kols;
  private final CurrentUserContext current;

  public KolItemService(
      KolItemMapper items,
      ProductMapper products,
      ShopMapper shops,
      KolApi kols,
      CurrentUserContext current) {
    this.items = items;
    this.products = products;
    this.shops = shops;
    this.kols = kols;
    this.current = current;
  }

  public PageResult<KolItemDTO> page(KolItemFilterDTO filter) {
    AccountDTO user = current.user();
    List<Long> ownKolIds =
        user.ownScope()
            ? kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).toList()
            : List.of();
    if (user.ownScope() && ownKolIds.isEmpty()) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    if (!user.ownScope()
        && blank(filter.kolCode())
        && blank(filter.productCode())
        && blank(filter.weidianItemId())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Long kolId = null;
    if (filter.kolCode() != null && !filter.kolCode().isBlank()) {
      KolRefDTO kol = kols.getByCode(filter.kolCode().strip());
      if (kol == null) {
        return new PageResult<>(java.util.List.of(), 0, filter.page(), filter.size());
      }
      kolId = kol.id();
    }
    Long productId = null;
    if (filter.productCode() != null && !filter.productCode().isBlank()) {
      ProductDO product =
          products.selectOne(
              new LambdaQueryWrapper<ProductDO>()
                  .eq(
                      ProductDO::getProductCode,
                      filter.productCode().strip().toUpperCase(Locale.ROOT))
                  .last("LIMIT 1"));
      if (product == null) {
        return new PageResult<>(java.util.List.of(), 0, filter.page(), filter.size());
      }
      productId = product.getId();
    }
    var query =
        new LambdaQueryWrapper<KolItemDO>()
            .in(user.ownScope(), KolItemDO::getKolId, ownKolIds)
            .eq(kolId != null, KolItemDO::getKolId, kolId)
            .eq(productId != null, KolItemDO::getProductId, productId)
            .eq(filter.shopId() != null, KolItemDO::getShopId, filter.shopId())
            .eq(
                filter.weidianItemId() != null && !filter.weidianItemId().isBlank(),
                KolItemDO::getWeidianItemId,
                filter.weidianItemId() == null ? null : filter.weidianItemId().strip())
            .eq(
                filter.source() != null && !filter.source().isBlank(),
                KolItemDO::getSource,
                filter.source() == null ? null : filter.source().strip())
            .isNull(filter.currentOnly(), KolItemDO::getValidTo)
            .orderByDesc(KolItemDO::getValidFrom, KolItemDO::getId);
    var result = items.selectPage(new Page<>(filter.page(), filter.size()), query);
    Map<Long, KolRefDTO> kolMap = kolMap(result.getRecords().stream().map(KolItemDO::getKolId));
    Map<Long, ProductDO> productMap =
        productMap(result.getRecords().stream().map(KolItemDO::getProductId));
    Map<Long, ShopDO> shopMap = shopMap(result.getRecords().stream().map(KolItemDO::getShopId));
    return new PageResult<>(
        result.getRecords().stream().map(row -> toDto(row, kolMap, productMap, shopMap)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  private Map<Long, KolRefDTO> kolMap(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, KolRefDTO> map = new HashMap<>();
    for (KolRefDTO kol : kols.listByIds(wanted)) {
      map.put(kol.id(), kol);
    }
    return map;
  }

  private Map<Long, ProductDO> productMap(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, ProductDO> map = new HashMap<>();
    if (wanted.isEmpty()) {
      return map;
    }
    for (ProductDO p :
        products.selectList(new LambdaQueryWrapper<ProductDO>().in(ProductDO::getId, wanted))) {
      map.put(p.getId(), p);
    }
    return map;
  }

  private Map<Long, ShopDO> shopMap(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, ShopDO> map = new HashMap<>();
    if (wanted.isEmpty()) {
      return map;
    }
    for (ShopDO s : shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, wanted))) {
      map.put(s.getId(), s);
    }
    return map;
  }

  private static KolItemDTO toDto(
      KolItemDO row,
      Map<Long, KolRefDTO> kols,
      Map<Long, ProductDO> products,
      Map<Long, ShopDO> shops) {
    KolRefDTO kol = kols.get(row.getKolId());
    ProductDO product = products.get(row.getProductId());
    ShopDO shop = shops.get(row.getShopId());
    return new KolItemDTO(
        row.getId(),
        row.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        row.getProductId(),
        product == null ? null : product.getProductCode(),
        product == null ? null : product.getName(),
        row.getShopId(),
        shop == null ? null : shop.getShopName(),
        shop == null ? null : shop.getWeidianShopId(),
        row.getWeidianItemId(),
        row.getItemName(),
        row.getSource(),
        row.getValidFrom(),
        row.getValidTo());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
