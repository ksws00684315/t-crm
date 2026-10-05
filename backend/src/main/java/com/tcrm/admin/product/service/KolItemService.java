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
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Kol item mapping query (task 05 §4.3, §4.4). SUPER_ADMIN, OPERATOR+ALL and FINANCE may read;
 * OPERATOR+OWN gets 403.
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
    requireReader();
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
            .eq(kolId != null, KolItemDO::getKolId, kolId)
            .eq(productId != null, KolItemDO::getProductId, productId)
            .eq(filter.shopId() != null, KolItemDO::getShopId, filter.shopId())
            .eq(
                filter.weidianItemId() != null && !filter.weidianItemId().isBlank(),
                KolItemDO::getWeidianItemId,
                filter.weidianItemId() == null ? null : filter.weidianItemId().strip())
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

  AccountDTO requireReader() {
    AccountDTO user = current.user();
    if (ProductService.own(user)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user;
  }

  AccountDTO requireWriter() {
    AccountDTO user = current.user();
    if (user.role() == Role.FINANCE || ProductService.own(user)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user;
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
        row.getValidFrom(),
        row.getValidTo());
  }
}
