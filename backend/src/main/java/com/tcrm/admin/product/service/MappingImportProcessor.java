package com.tcrm.admin.product.service;

import static com.tcrm.admin.product.service.MappingImportColumn.KOL_CODE;
import static com.tcrm.admin.product.service.MappingImportColumn.PRODUCT_CODE;
import static com.tcrm.admin.product.service.MappingImportColumn.SHOP_ID;
import static com.tcrm.admin.product.service.MappingImportColumn.WEIDIAN_ITEM_ID;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.task.api.ImportRowProcessor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports kol item mappings (task 05 §4.3): one transaction per row; identical current mapping is
 * idempotent; replacing the exclusive item closes the old row then inserts.
 */
class MappingImportProcessor implements ImportRowProcessor {
  static final String SHOP_REQUIRED = "店铺 ID 不能为空";
  static final String SHOP_FORMAT = "店铺 ID 应为 1～32 位数字";
  static final String SHOP_MISSING = "店铺不存在或已停用";
  static final String ITEM_REQUIRED = "专属商品 ID 不能为空";
  static final String ITEM_FORMAT = "专属商品 ID 应为 1～32 位数字";
  static final String ITEM_DUPLICATE = "同一专属商品在文件中重复";
  static final String KOL_REQUIRED = "达人编号不能为空";
  static final String KOL_MISSING = "达人编号不存在或已停用";
  static final String PRODUCT_REQUIRED = "原商品编码不能为空";
  static final String PRODUCT_MISSING = "原商品编码不存在";
  static final String PAIR_DUPLICATE = "同一达人与原商品在文件中重复";
  static final String IS_ORIGINAL = "专属商品不能是原商品本身";
  static final String OCCUPIED_PREFIX = "专属商品已被占用：达人 ";
  static final String CONFLICT = "数据已被其他操作修改，请重新导入该行";

  private final Long actorId;
  private final ShopService shops;
  private final ProductService products;
  private final KolItemMapper items;
  private final ProductMapper productMapper;
  private final KolApi kols;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;
  private final Set<String> seenExclusive = new HashSet<>();
  private final Set<String> seenPairs = new HashSet<>();

  MappingImportProcessor(
      Long actorId,
      ShopService shops,
      ProductService products,
      KolItemMapper items,
      ProductMapper productMapper,
      KolApi kols,
      TransactionTemplate tx,
      ApplicationEventPublisher events) {
    this.actorId = actorId;
    this.shops = shops;
    this.products = products;
    this.items = items;
    this.productMapper = productMapper;
    this.kols = kols;
    this.tx = tx;
    this.events = events;
  }

  @Override
  public List<String> process(List<String> row) {
    List<String> errors = new ArrayList<>();
    String shopWeidian = SHOP_ID.of(row);
    ShopDO shop = null;
    if (shopWeidian.isEmpty()) {
      errors.add(SHOP_REQUIRED);
    } else if (!ProductRules.isWeidianId(shopWeidian)) {
      errors.add(SHOP_FORMAT);
    } else {
      shop = shops.findEnabledByWeidianId(shopWeidian);
      if (shop == null) {
        errors.add(SHOP_MISSING);
      }
    }
    String exclusiveId = WEIDIAN_ITEM_ID.of(row);
    if (exclusiveId.isEmpty()) {
      errors.add(ITEM_REQUIRED);
    } else if (!ProductRules.isWeidianId(exclusiveId)) {
      errors.add(ITEM_FORMAT);
    } else if (shop != null && !seenExclusive.add(shop.getId() + ":" + exclusiveId)) {
      errors.add(ITEM_DUPLICATE);
    }
    String kolCode = KOL_CODE.of(row).toUpperCase(Locale.ROOT);
    KolRefDTO kol = null;
    if (kolCode.isEmpty()) {
      errors.add(KOL_REQUIRED);
    } else {
      kol = kols.getByCode(kolCode);
      if (kol == null || !kol.enabled()) {
        errors.add(KOL_MISSING);
      }
    }
    String productCode = PRODUCT_CODE.of(row).toUpperCase(Locale.ROOT);
    ProductDO product = null;
    if (productCode.isEmpty()) {
      errors.add(PRODUCT_REQUIRED);
    } else {
      product = products.findByCode(productCode);
      if (product == null) {
        errors.add(PRODUCT_MISSING);
      }
    }
    if (kol != null && product != null && !seenPairs.add(kol.id() + ":" + product.getId())) {
      errors.add(PAIR_DUPLICATE);
    }
    if (!errors.isEmpty() || shop == null || kol == null || product == null) {
      return errors;
    }
    if (!ProductRules.isWeidianId(exclusiveId)) {
      return errors;
    }
    // Exclusive item must not be an original product.
    if (productMapper.existsAliveShopItem(shop.getId(), exclusiveId)) {
      return List.of(IS_ORIGINAL);
    }
    try {
      return apply(shop, exclusiveId, kol, product);
    } catch (DuplicateKeyException e) {
      return List.of(CONFLICT);
    }
  }

  private List<String> apply(ShopDO shop, String exclusiveId, KolRefDTO kol, ProductDO product) {
    Instant now = Instant.now();
    KolItemDO byExclusive = items.findCurrentByShopItem(shop.getId(), exclusiveId);
    if (byExclusive != null) {
      if (byExclusive.getKolId().equals(kol.id())
          && byExclusive.getProductId().equals(product.getId())) {
        // Identical current mapping: idempotent success.
        return List.of();
      }
      KolRefDTO owner =
          kols.listByIds(List.of(byExclusive.getKolId())).stream().findFirst().orElse(null);
      ProductDO occupiedProduct = productMapper.selectById(byExclusive.getProductId());
      String ownerCode = owner == null ? "?" : owner.kolCode();
      String occupiedCode = occupiedProduct == null ? "?" : occupiedProduct.getProductCode();
      return List.of(OCCUPIED_PREFIX + ownerCode + "，原商品 " + occupiedCode);
    }
    KolItemDO byPair = items.findCurrentByKolProduct(kol.id(), product.getId());
    tx.executeWithoutResult(
        status -> {
          if (byPair != null) {
            // Replace exclusive item: close old then insert (same transaction).
            items.update(
                null,
                new LambdaUpdateWrapper<KolItemDO>()
                    .eq(KolItemDO::getId, byPair.getId())
                    .set(KolItemDO::getValidTo, now)
                    .set(KolItemDO::getUpdateBy, actorId)
                    .set(KolItemDO::getUpdateTime, now));
          }
          KolItemDO neu = new KolItemDO();
          neu.setKolId(kol.id());
          neu.setProductId(product.getId());
          neu.setShopId(shop.getId());
          neu.setWeidianItemId(exclusiveId);
          neu.setValidFrom(now);
          neu.setCreateBy(actorId);
          neu.setUpdateBy(actorId);
          items.insert(neu);
          // Registered in this transaction and applied to the link index afterwards (task 06b).
          TransactionalEvents.publish(events, new KolItemChangedEvent(kol.id(), product.getId()));
        });
    return List.of();
  }
}
