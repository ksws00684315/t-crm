package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.CopyTargetShopApi;
import com.tcrm.admin.kol.api.CopyTargetShopApi.CopyChannelDTO;
import com.tcrm.admin.kol.api.CopyTargetShopApi.CopyShopDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.product.api.ShopIntegrationApi;
import com.tcrm.admin.product.api.ShopStatusChangedEvent;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.MissingTenantException;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shop management (task 05 §4.1). The per-company shop limit is system-configurable. */
@Service
public class ShopService implements ShopAccessApi, CopyTargetShopApi {
  private final ShopMapper shops;
  private final CurrentUserContext current;
  private final ApplicationEventPublisher events;
  private final SystemConfigApi configs;
  private final ShopIntegrationApi integration;

  @Autowired
  public ShopService(
      ShopMapper shops,
      CurrentUserContext current,
      ApplicationEventPublisher events,
      SystemConfigApi configs,
      @Lazy ShopIntegrationApi integration) {
    this.shops = shops;
    this.current = current;
    this.events = events;
    this.configs = configs;
    this.integration = integration;
  }

  ShopService(
      ShopMapper shops,
      CurrentUserContext current,
      ApplicationEventPublisher events,
      SystemConfigApi configs) {
    this(shops, current, events, configs, null);
  }

  public PageResult<ShopDTO> page(
      String shopCode, String shopName, String channel, int page, int size) {
    LambdaQueryWrapper<ShopDO> query = new LambdaQueryWrapper<>();
    if (shopCode != null && !shopCode.isBlank()) {
      query.likeRight(ShopDO::getShopCode, shopCode.strip());
    }
    if (shopName != null && !shopName.isBlank()) {
      query.like(ShopDO::getShopName, shopName.strip());
    }
    if (channel != null && !channel.isBlank()) {
      query.eq(ShopDO::getChannel, channel.strip().toUpperCase(Locale.ROOT));
    }
    query.orderByAsc(ShopDO::getId);
    var result = shops.selectPage(new Page<>(page, size), query);
    List<ShopDTO> items = buildViews(result.getRecords());
    return new PageResult<>(items, result.getTotal(), page, size);
  }

  /**
   * Creates a shop. The count check and the insert run in one transaction behind a
   * transaction-level advisory lock, so concurrent requests cannot exceed the current configured
   * shop limit.
   */
  @Transactional(rollbackFor = Exception.class)
  public ShopDTO create(CreateShopCommand c) {
    Long tenantId = current.user().tenantId();
    if (tenantId == null) {
      throw new MissingTenantException("shop creation requires a company");
    }
    shops.lockForCreate(tenantId);
    if (shops.countAlive() >= configs.getInt("shop.max-per-company")) {
      throw new BusinessException(ErrorCode.SHOP_LIMIT_REACHED);
    }
    String weidianId = c.weidianShopId().strip();
    if (!ProductRules.isWeidianId(weidianId)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (TenantScope.callSystem(
        "weidian shop id uniqueness", () -> shops.countByWeidianId(weidianId) > 0)) {
      throw new BusinessException(ErrorCode.SHOP_WEIDIAN_ID_EXISTS);
    }
    AccountDTO user = current.user();
    ShopDO shop = new ShopDO();
    if (!"WEIDIAN".equals(c.channel())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    int maxItems = c.maxItems() == null ? 20000 : c.maxItems();
    if (maxItems < 1 || maxItems > 1000000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    shop.setShopName(c.shopName().strip());
    shop.setShopCode("SHOP-%06d".formatted(shops.nextShopCodeNumber()));
    shop.setChannel(c.channel());
    shop.setMaxItems(maxItems);
    shop.setCurrentItems(0);
    shop.setSource(false);
    shop.setCopyPoolStatus("EXITED");
    shop.setWeidianShopId(weidianId);
    shop.setStatus(1);
    shop.setRemark(ProductRules.blankToNull(c.remark()));
    shop.setDeleted(false);
    try {
      shops.insert(shop);
    } catch (DuplicateKeyException e) {
      throw new BusinessException(ErrorCode.SHOP_WEIDIAN_ID_EXISTS);
    }
    ShopDO saved = shops.selectById(shop.getId());
    return buildViews(List.of(saved == null ? shop : saved)).getFirst();
  }

  public void update(Long id, EditShopCommand c) {
    ShopDO shop = require(id);
    if (!"WEIDIAN".equals(c.channel()) || !ProductRules.isWeidianId(c.weidianShopId())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (!Objects.equals(shop.getWeidianShopId(), c.weidianShopId())
        && TenantScope.callSystem(
            "weidian shop id uniqueness", () -> shops.countByWeidianId(c.weidianShopId()) > 0)) {
      throw new BusinessException(ErrorCode.SHOP_WEIDIAN_ID_EXISTS);
    }
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getShopName, c.shopName().strip())
            .set(ShopDO::getChannel, c.channel())
            .set(ShopDO::getWeidianShopId, c.weidianShopId().strip())
            .set(c.maxItems() != null, ShopDO::getMaxItems, c.maxItems())
            .set(ShopDO::getRemark, ProductRules.blankToNull(c.remark()))
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
  }

  /**
   * Enables or disables the shop and publishes {@link ShopStatusChangedEvent} in this transaction;
   * the link index is registered before commit and applied afterwards (task 06b).
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, int status) {
    require(id);
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getStatus, status)
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
    TransactionalEvents.publish(events, new ShopStatusChangedEvent(id));
  }

  /** Alive and enabled shop, or SHOP_NOT_FOUND / SHOP_DISABLED. */
  public ShopDO requireEnabled(Long id) {
    ShopDO shop = require(id);
    if (shop.getStatus() == null || shop.getStatus() != 1) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
    return shop;
  }

  /** Alive shop by weidian shop id (enabled), or Chinese failure reason for import. */
  public ShopDO findEnabledByWeidianId(String weidianShopId) {
    return shops.selectOne(
        new LambdaQueryWrapper<ShopDO>()
            .eq(ShopDO::getWeidianShopId, weidianShopId)
            .eq(ShopDO::getStatus, 1)
            .last("LIMIT 1"));
  }

  public ShopDO require(Long id) {
    ShopDO shop = shops.selectById(id);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    return shop;
  }

  public ShopDO getById(Long id) {
    return shops.selectById(id);
  }

  @Override
  public ShopAccessDTO findShop(Long id) {
    ShopDO shop = shops.selectById(id);
    return shop == null
        ? null
        : new ShopAccessDTO(
            shop.getId(), shop.getShopName(), shop.getWeidianShopId(), shop.getStatus());
  }

  @Override
  public ShopAccessDTO findEnabledByWeidianShopId(String weidianShopId) {
    ShopDO shop = findEnabledByWeidianId(weidianShopId);
    return shop == null
        ? null
        : new ShopAccessDTO(
            shop.getId(), shop.getShopName(), shop.getWeidianShopId(), shop.getStatus());
  }

  @Override
  public List<ShopAccessDTO> listShops() {
    return shops.selectList(new LambdaQueryWrapper<ShopDO>().orderByAsc(ShopDO::getId)).stream()
        .map(
            shop ->
                new ShopAccessDTO(
                    shop.getId(), shop.getShopName(), shop.getWeidianShopId(), shop.getStatus()))
        .toList();
  }

  @Override
  public void requireEnabled(long shopId) {
    ShopDO shop = shops.selectById(shopId);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    if (shop.getStatus() == null || shop.getStatus() != 1) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
  }

  @Override
  public Map<Long, String> namesByIds(Collection<Long> shopIds) {
    Map<Long, String> byId = new HashMap<>();
    if (shopIds == null || shopIds.isEmpty()) {
      return byId;
    }
    List<Long> ids = shopIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return byId;
    }
    for (ShopDO shop : shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, ids))) {
      byId.put(shop.getId(), shop.getShopName());
    }
    return byId;
  }

  @Override
  public Map<Long, String> codesByIds(Collection<Long> shopIds) {
    Map<Long, String> byId = new HashMap<>();
    if (shopIds == null || shopIds.isEmpty()) {
      return byId;
    }
    List<Long> ids = shopIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return byId;
    }
    for (ShopDO shop : shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, ids))) {
      byId.put(shop.getId(), shop.getShopCode());
    }
    return byId;
  }

  static ShopDTO toDto(ShopDO shop) {
    return toDto(shop, null);
  }

  private static ShopDTO toDto(ShopDO shop, ShopIntegrationApi.ShopAuthorizationDTO auth) {
    String authStatus =
        auth == null || !"AUTHORIZED".equals(auth.status())
            ? auth != null && "EXPIRED".equals(auth.status()) ? "EXPIRED" : "UNAUTHORIZED"
            : "AUTHORIZED";
    if (auth != null && auth.expiresAt() != null && !auth.expiresAt().isAfter(Instant.now())) {
      authStatus = "EXPIRED";
    }
    String reason = ineligibleReason(shop, authStatus);
    return new ShopDTO(
        shop.getId(),
        shop.getShopCode(),
        shop.getShopName(),
        shop.getChannel(),
        shop.getWeidianShopId(),
        shop.getStatus(),
        shop.getRemark(),
        Boolean.TRUE.equals(shop.getSource()),
        Boolean.TRUE.equals(shop.getSource()) ? "NOT_APPLICABLE" : shop.getCopyPoolStatus(),
        shop.getMaxItems(),
        shop.getCurrentItems(),
        shop.getItemsRefreshedAt(),
        authStatus,
        auth == null ? null : auth.expiresAt(),
        new ShopDTO.ApiChecksStatus(
            new ShopDTO.ApiStatus(
                shop.getProductApiOk(), shop.getProductApiCheckedAt(), shop.getProductApiError()),
            new ShopDTO.ApiStatus(
                shop.getOrderApiOk(), shop.getOrderApiCheckedAt(), shop.getOrderApiError()),
            new ShopDTO.ApiStatus(
                shop.getRefundApiOk(), shop.getRefundApiCheckedAt(), shop.getRefundApiError())),
        reason == null,
        reason,
        shop.getCreateTime(),
        shop.getUpdateTime());
  }

  private static String ineligibleReason(ShopDO shop, String authStatus) {
    if (!Integer.valueOf(1).equals(shop.getStatus())) {
      return "DISABLED";
    }
    if (!"AUTHORIZED".equals(authStatus)) {
      return "EXPIRED".equals(authStatus) ? "AUTH_EXPIRED" : "UNAUTHORIZED";
    }
    if (Boolean.TRUE.equals(shop.getSource())) {
      return "SOURCE_SHOP";
    }
    if (!"JOINED".equals(shop.getCopyPoolStatus())) {
      return "POOL_EXITED";
    }
    if (shop.getMaxItems() == null
        || shop.getCurrentItems() == null
        || shop.getCurrentItems() >= shop.getMaxItems()) {
      return "CAPACITY_FULL";
    }
    return null;
  }

  private List<ShopDTO> buildViews(List<ShopDO> rows) {
    List<Long> ids = rows.stream().map(ShopDO::getId).toList();
    Map<Long, ShopIntegrationApi.ShopAuthorizationDTO> statuses =
        integration == null ? Map.of() : integration.statuses(ids);
    return rows.stream().map(row -> toDto(row, statuses.get(row.getId()))).toList();
  }

  /** Marks this shop as a member of the automatic copy pool. */
  @Transactional(rollbackFor = Exception.class)
  public void joinCopyPool(Long id) {
    ShopDO shop = require(id);
    if (Boolean.TRUE.equals(shop.getSource())) {
      throw new BusinessException(ErrorCode.SHOP_SOURCE_CANNOT_JOIN_POOL);
    }
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, shop.getId())
            .set(ShopDO::getCopyPoolStatus, "JOINED")
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
  }

  /** Removes this shop from the automatic copy pool. */
  @Transactional(rollbackFor = Exception.class)
  public void exitCopyPool(Long id) {
    require(id);
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .eq(ShopDO::getCopyPoolStatus, "JOINED")
            .set(ShopDO::getCopyPoolStatus, "EXITED")
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
  }

  @Transactional(rollbackFor = Exception.class)
  public void markSource(Long id, boolean source) {
    require(id);
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getSource, source)
            .set(source, ShopDO::getCopyPoolStatus, "EXITED")
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, Instant.now()));
  }

  public ShopDTO refreshCapacity(Long id) {
    ShopDO shop = require(id);
    if (integration == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    int count = integration.countOnSaleItems(id);
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getCurrentItems, count)
            .set(ShopDO::getItemsRefreshedAt, Instant.now())
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, Instant.now()));
    return buildViews(List.of(require(id))).getFirst();
  }

  public ShopDTO checkApis(long id) {
    require(id);
    if (integration == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    ShopIntegrationApi.ShopApiChecksDTO checks = integration.checkApis(id);
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getProductApiOk, checks.product().ok())
            .set(ShopDO::getProductApiCheckedAt, checks.product().checkedAt())
            .set(ShopDO::getProductApiError, checks.product().error())
            .set(ShopDO::getOrderApiOk, checks.order().ok())
            .set(ShopDO::getOrderApiCheckedAt, checks.order().checkedAt())
            .set(ShopDO::getOrderApiError, checks.order().error())
            .set(ShopDO::getRefundApiOk, checks.refund().ok())
            .set(ShopDO::getRefundApiCheckedAt, checks.refund().checkedAt())
            .set(ShopDO::getRefundApiError, checks.refund().error())
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
    return buildViews(List.of(require(id))).getFirst();
  }

  @Override
  public List<CopyChannelDTO> copyChannels() {
    List<ShopDO> rows =
        shops.selectList(new LambdaQueryWrapper<ShopDO>().orderByAsc(ShopDO::getId));
    List<ShopDTO> eligible = buildViews(rows).stream().filter(ShopDTO::copyEligible).toList();
    List<CopyShopDTO> shopRows =
        eligible.stream()
            .map(
                row ->
                    new CopyShopDTO(
                        row.id(),
                        row.shopCode(),
                        row.shopName(),
                        availableCapacity(row.id(), row.maxItems(), row.currentItems())))
            .filter(row -> row.remainingCapacity() > 0)
            .toList();
    return List.of(
        new CopyChannelDTO(
            "WEIDIAN", shopRows.stream().mapToInt(CopyShopDTO::remainingCapacity).sum(), shopRows));
  }

  private int availableCapacity(long shopId, int maxItems, int currentItems) {
    return Math.max(0, maxItems - currentItems - shops.countReservedItems(shopId));
  }

  @Override
  public void validateCopyTarget(long shopId, String channel, int quantity) {
    shops.lockShop(shopId);
    ShopDO shop = require(shopId);
    ShopDTO row = buildViews(List.of(shop)).getFirst();
    if (!"WEIDIAN".equals(channel) || !row.copyEligible()) {
      throw ineligibleError(row.copyIneligibleReason());
    }
    if (quantity > availableCapacity(shopId, shop.getMaxItems(), shop.getCurrentItems())) {
      throw new BusinessException(ErrorCode.SHOP_COPY_CAPACITY_EXCEEDED);
    }
  }

  @Override
  public void requireCopyEligible(long shopId, String channel) {
    ShopDO shop = require(shopId);
    ShopDTO row = buildViews(List.of(shop)).getFirst();
    if (!"WEIDIAN".equals(channel) || !row.copyEligible()) {
      throw ineligibleError(row.copyIneligibleReason());
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public long allocateCopyTarget(String channel, int quantity) {
    if (!"WEIDIAN".equals(channel)) {
      throw new BusinessException(ErrorCode.COPY_TARGET_SHOP_MISSING);
    }
    List<Long> candidateIds =
        copyChannels().getFirst().shops().stream().map(CopyShopDTO::id).sorted().toList();
    // Lock in stable ID order to avoid deadlocks, then compare fresh capacities while all
    // candidate rows are held. This preserves the largest-remaining-capacity rule under races.
    candidateIds.forEach(shops::lockShop);
    List<ShopDTO> candidates =
        candidateIds.stream()
            .map(this::require)
            .map(shop -> buildViews(List.of(shop)).getFirst())
            .filter(ShopDTO::copyEligible)
            .sorted(
                (left, right) ->
                    Integer.compare(
                        availableCapacity(right.id(), right.maxItems(), right.currentItems()),
                        availableCapacity(left.id(), left.maxItems(), left.currentItems())))
            .toList();
    for (ShopDTO candidate : candidates) {
      int remaining =
          availableCapacity(candidate.id(), candidate.maxItems(), candidate.currentItems());
      if (remaining >= quantity) {
        return candidate.id();
      }
    }
    throw new BusinessException(ErrorCode.COPY_SHOP_CAPACITY_INSUFFICIENT);
  }

  private static BusinessException ineligibleError(String reason) {
    return switch (reason == null ? "" : reason) {
      case "DISABLED" -> new BusinessException(ErrorCode.SHOP_DISABLED);
      case "UNAUTHORIZED", "AUTH_EXPIRED" ->
          new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
      case "CAPACITY_FULL" -> new BusinessException(ErrorCode.SHOP_COPY_CAPACITY_EXCEEDED);
      default -> new BusinessException(ErrorCode.COPY_TARGET_SHOP_MISSING);
    };
  }
}
