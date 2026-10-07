package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.ProductStatusChangedEvent;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TransactionalEvents;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Original product management (task 05 §4.2, §4.4). Writes require SUPER_ADMIN or OPERATOR+ALL;
 * everyone may read.
 */
@Service
public class ProductService {
  private final ProductMapper products;
  private final ShopMapper shops;
  private final KolItemMapper kolItems;
  private final ProductCodeGenerator codes;
  private final CurrentUserContext current;
  private final ApplicationEventPublisher events;

  public ProductService(
      ProductMapper products,
      ShopMapper shops,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      CurrentUserContext current,
      ApplicationEventPublisher events) {
    this.products = products;
    this.shops = shops;
    this.kolItems = kolItems;
    this.codes = codes;
    this.current = current;
    this.events = events;
  }

  /** True for a BD: OPERATOR with OWN data scope — no product writes or mapping access. */
  static boolean own(AccountDTO user) {
    return user.role() == Role.OPERATOR && user.dataScope() == DataScope.OWN;
  }

  public PageResult<ProductDTO> page(ProductFilterDTO filter) {
    String keyword = filter.keyword() == null ? "" : filter.keyword().strip();
    String pattern = "%" + ProductRules.escapeLike(keyword) + "%";
    String category =
        filter.category() == null || filter.category().isBlank() ? null : filter.category().strip();
    var query =
        new LambdaQueryWrapper<ProductDO>()
            .apply(
                !keyword.isEmpty(),
                "(product_code ILIKE {0} OR name ILIKE {1} OR weidian_item_id ILIKE {2})",
                pattern,
                pattern,
                pattern)
            .eq(filter.shopId() != null, ProductDO::getShopId, filter.shopId())
            .eq(filter.status() != null, ProductDO::getStatus, filter.status())
            .eq(category != null, ProductDO::getCategory, category)
            .orderByAsc(ProductDO::getProductCode, ProductDO::getId);
    var result = products.selectPage(new Page<>(filter.page(), filter.size()), query);
    Map<Long, ShopDO> shopMap = shopMap(result.getRecords().stream().map(ProductDO::getShopId));
    return new PageResult<>(
        result.getRecords().stream().map(p -> toDto(p, shopMap)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  public ProductDTO get(Long id) {
    ProductDO product = require(id);
    return toDto(product, shopMap(Stream.of(product.getShopId())));
  }

  /**
   * Updates editable product fields only (name, category, image, price, rate). Uses a column
   * whitelist so concurrent status changes cannot be overwritten.
   */
  public void update(Long id, EditProductCommand c) {
    requireWriter();
    require(id);
    Instant now = Instant.now();
    products.update(
        null,
        new LambdaUpdateWrapper<ProductDO>()
            .eq(ProductDO::getId, id)
            .set(ProductDO::getName, c.name().strip())
            .set(ProductDO::getCategory, ProductRules.blankToNull(c.category()))
            .set(ProductDO::getImageUrl, ProductRules.blankToNull(c.imageUrl()))
            .set(ProductDO::getPrice, c.price())
            .set(ProductDO::getRate, c.rate())
            .set(ProductDO::getUpdateBy, current.id())
            .set(ProductDO::getUpdateTime, now));
  }

  /**
   * Puts the product on or off sale and publishes {@link ProductStatusChangedEvent} in this
   * transaction; the link index is registered before commit and applied afterwards (task 06b).
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, int status) {
    requireWriter();
    require(id);
    Instant now = Instant.now();
    products.update(
        null,
        new LambdaUpdateWrapper<ProductDO>()
            .eq(ProductDO::getId, id)
            .set(ProductDO::getStatus, status)
            .set(ProductDO::getUpdateBy, current.id())
            .set(ProductDO::getUpdateTime, now));
    TransactionalEvents.publish(events, new ProductStatusChangedEvent(id));
  }

  ProductDO require(Long id) {
    ProductDO product = products.selectById(id);
    if (product == null) {
      throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
    }
    return product;
  }

  AccountDTO requireWriter() {
    AccountDTO user = current.user();
    if (user.role() == Role.FINANCE || own(user)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user;
  }

  /** Used by import: reject when (shop, weidian item) is already an exclusive item. */
  boolean isExclusiveItem(Long shopId, String weidianItemId) {
    return kolItems.existsCurrentShopItem(shopId, weidianItemId);
  }

  ProductCodeGenerator codes() {
    return codes;
  }

  ProductMapper products() {
    return products;
  }

  private Map<Long, ShopDO> shopMap(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
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

  static ProductDTO toDto(ProductDO p, Map<Long, ShopDO> shops) {
    ShopDO shop = shops.get(p.getShopId());
    return new ProductDTO(
        p.getId(),
        p.getProductCode(),
        p.getShopId(),
        shop == null ? null : shop.getShopName(),
        shop == null ? null : shop.getWeidianShopId(),
        p.getWeidianItemId(),
        p.getName(),
        p.getCategory(),
        p.getImageUrl(),
        p.getPrice(),
        p.getRate(),
        p.getStatus(),
        p.getCreateTime(),
        p.getUpdateTime());
  }

  /** Lookup by code (case-insensitive input, stored upper case). */
  public ProductDO findByCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    return products.selectOne(
        new LambdaQueryWrapper<ProductDO>()
            .eq(ProductDO::getProductCode, code.strip().toUpperCase(Locale.ROOT))
            .last("LIMIT 1"));
  }
}
