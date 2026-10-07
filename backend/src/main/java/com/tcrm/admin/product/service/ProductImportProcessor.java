package com.tcrm.admin.product.service;

import static com.tcrm.admin.product.service.ProductImportColumn.CATEGORY;
import static com.tcrm.admin.product.service.ProductImportColumn.CODE;
import static com.tcrm.admin.product.service.ProductImportColumn.IMAGE_URL;
import static com.tcrm.admin.product.service.ProductImportColumn.NAME;
import static com.tcrm.admin.product.service.ProductImportColumn.PRICE;
import static com.tcrm.admin.product.service.ProductImportColumn.RATE;
import static com.tcrm.admin.product.service.ProductImportColumn.SHOP_ID;
import static com.tcrm.admin.product.service.ProductImportColumn.WEIDIAN_ITEM_ID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportRowProcessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports original products (task 05 §4.2): insert-or-update by (shop, weidian item id); blank
 * cells on update mean "leave unchanged"; codes are immutable.
 */
class ProductImportProcessor implements ImportRowProcessor {
  static final String CODE_FORMAT = "原商品编码格式不正确，应为 1～16 位大写字母、数字、下划线或连字符";
  static final String CODE_DUPLICATE = "原商品编码在文件中重复";
  static final String CODE_EXISTS = "原商品编码已存在";
  static final String CODE_IMMUTABLE = "原商品编码不可修改";
  static final String SHOP_REQUIRED = "店铺 ID 不能为空";
  static final String SHOP_FORMAT = "店铺 ID 应为 1～32 位数字";
  static final String SHOP_MISSING = "店铺不存在或已停用";
  static final String ITEM_REQUIRED = "微店商品 ID 不能为空";
  static final String ITEM_FORMAT = "微店商品 ID 应为 1～32 位数字";
  static final String ITEM_DUPLICATE = "同一店铺下微店商品 ID 在文件中重复";
  static final String NAME_REQUIRED = "新增时商品名称不能为空";
  static final String NAME_LENGTH = "商品名称不能超过 200 个字符";
  static final String CATEGORY_LENGTH = "分类不能超过 64 个字符";
  static final String IMAGE_FORMAT = "图片地址须为 https 开头，且不超过 512 个字符";
  static final String PRICE_REQUIRED = "新增时价格不能为空";
  static final String PRICE_FORMAT = "价格应为非负数字，最多 2 位小数";
  static final String RATE_FORMAT = "商品佣金比例应为 0～100 的数字，最多 2 位小数";
  static final String EXCLUSIVE = "该微店商品 ID 已是专属商品，不能登记为原商品";
  static final String CONFLICT = "数据已被其他操作修改，请重新导入该行";

  private final Long actorId;
  private final ShopService shops;
  private final ProductMapper products;
  private final KolItemMapper kolItems;
  private final ProductCodeGenerator codes;
  private final TransactionTemplate tx;
  private final Set<String> seenCodes = new HashSet<>();
  private final Set<String> seenShopItems = new HashSet<>();

  ProductImportProcessor(
      Long actorId,
      ShopService shops,
      ProductMapper products,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      TransactionTemplate tx) {
    this.actorId = actorId;
    this.shops = shops;
    this.products = products;
    this.kolItems = kolItems;
    this.codes = codes;
    this.tx = tx;
  }

  @Override
  public List<String> process(List<String> row) {
    List<String> errors = new ArrayList<>();
    String codeText = CODE.of(row).toUpperCase(Locale.ROOT);
    String code = codeText.isEmpty() ? null : codeText;
    if (code != null) {
      if (!ProductRules.isCode(code)) {
        errors.add(CODE_FORMAT);
      } else if (!seenCodes.add(code)) {
        errors.add(CODE_DUPLICATE);
      }
    }
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
    String itemId = WEIDIAN_ITEM_ID.of(row);
    if (itemId.isEmpty()) {
      errors.add(ITEM_REQUIRED);
    } else if (!ProductRules.isWeidianId(itemId)) {
      errors.add(ITEM_FORMAT);
    } else if (shop != null && !seenShopItems.add(shop.getId() + ":" + itemId)) {
      errors.add(ITEM_DUPLICATE);
    }
    String name = NAME.of(row);
    String category = CATEGORY.of(row);
    String image = IMAGE_URL.of(row);
    String priceText = PRICE.of(row);
    String rateText = RATE.of(row);
    if (!category.isEmpty() && ProductRules.length(category) > ProductRules.MAX_CATEGORY) {
      errors.add(CATEGORY_LENGTH);
    }
    if (!image.isEmpty()
        && (ProductRules.length(image) > ProductRules.MAX_IMAGE_URL
            || !ProductRules.isHttpsUrl(image))) {
      errors.add(IMAGE_FORMAT);
    }
    BigDecimal price = null;
    if (!priceText.isEmpty()) {
      price = ProductRules.parsePrice(priceText);
      if (price == null) {
        errors.add(PRICE_FORMAT);
      }
    }
    BigDecimal rate = null;
    if (!rateText.isEmpty()) {
      rate = ProductRules.percentToRate(rateText);
      if (rate == null) {
        errors.add(RATE_FORMAT);
      }
    }
    if (!name.isEmpty() && ProductRules.length(name) > ProductRules.MAX_NAME) {
      errors.add(NAME_LENGTH);
    }
    if (!errors.isEmpty()
        || shop == null
        || itemId.isEmpty()
        || !ProductRules.isWeidianId(itemId)) {
      return errors;
    }
    ProductDO existing =
        products.selectOne(
            new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getShopId, shop.getId())
                .eq(ProductDO::getWeidianItemId, itemId)
                .last("LIMIT 1"));
    try {
      if (existing == null) {
        return insert(shop, itemId, code, name, category, image, price, rate, errors);
      }
      return update(existing, code, name, category, image, price, rate, errors);
    } catch (BusinessException e) {
      return List.of(
          switch (e.getErrorCode()) {
            case PRODUCT_CODE_EXISTS -> CODE_EXISTS;
            case PRODUCT_CODE_IMMUTABLE -> CODE_IMMUTABLE;
            case PRODUCT_IS_EXCLUSIVE_ITEM -> EXCLUSIVE;
            case PRODUCT_WEIDIAN_ID_EXISTS -> ITEM_DUPLICATE;
            case COMMON_CONFLICT -> CONFLICT;
            default -> throw e;
          });
    }
  }

  private List<String> insert(
      ShopDO shop,
      String itemId,
      String code,
      String name,
      String category,
      String image,
      BigDecimal price,
      BigDecimal rate,
      List<String> errors) {
    if (name.isEmpty()) {
      errors.add(NAME_REQUIRED);
    }
    if (price == null) {
      errors.add(PRICE_REQUIRED);
    }
    if (code != null && products.existsCode(code)) {
      errors.add(CODE_EXISTS);
    }
    if (kolItems.existsCurrentShopItem(shop.getId(), itemId)) {
      errors.add(EXCLUSIVE);
    }
    if (!errors.isEmpty()) {
      return errors;
    }
    ProductDO product = new ProductDO();
    product.setShopId(shop.getId());
    product.setWeidianItemId(itemId);
    product.setName(name);
    product.setCategory(category.isEmpty() ? null : category);
    product.setImageUrl(image.isEmpty() ? null : image);
    product.setPrice(price);
    product.setRate(rate);
    product.setStatus(1);
    product.setCreateBy(actorId);
    product.setUpdateBy(actorId);
    product.setDeleted(false);
    for (int attempt = 1; ; attempt++) {
      product.setId(null);
      product.setProductCode(code != null ? code : codes.next());
      try {
        tx.executeWithoutResult(
            s -> {
              products.lockShopItem(ProductRules.shopItemLockKey(shop.getId(), itemId));
              if (kolItems.existsCurrentShopItem(shop.getId(), itemId)) {
                throw new BusinessException(ErrorCode.PRODUCT_IS_EXCLUSIVE_ITEM);
              }
              products.insert(product);
            });
        return List.of();
      } catch (DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_crm_product_shop_item")) {
          throw new BusinessException(ErrorCode.PRODUCT_WEIDIAN_ID_EXISTS);
        }
        if (code != null || !message.contains("uk_crm_product_product_code") || attempt >= 3) {
          throw new BusinessException(
              message.contains("uk_crm_product_product_code")
                  ? ErrorCode.PRODUCT_CODE_EXISTS
                  : ErrorCode.COMMON_CONFLICT);
        }
      }
    }
  }

  private List<String> update(
      ProductDO existing,
      String code,
      String name,
      String category,
      String image,
      BigDecimal price,
      BigDecimal rate,
      List<String> errors) {
    if (code != null && !code.equals(existing.getProductCode())) {
      errors.add(CODE_IMMUTABLE);
    }
    if (!errors.isEmpty()) {
      return errors;
    }
    Instant now = Instant.now();
    LambdaUpdateWrapper<ProductDO> update =
        new LambdaUpdateWrapper<ProductDO>()
            .eq(ProductDO::getId, existing.getId())
            .set(ProductDO::getUpdateBy, actorId)
            .set(ProductDO::getUpdateTime, now);
    // Blank cells mean leave unchanged; import cannot clear fields (task 05 §4.2).
    if (!name.isEmpty()) {
      update.set(ProductDO::getName, name);
    }
    if (!category.isEmpty()) {
      update.set(ProductDO::getCategory, category);
    }
    if (!image.isEmpty()) {
      update.set(ProductDO::getImageUrl, image);
    }
    if (price != null) {
      update.set(ProductDO::getPrice, price);
    }
    if (rate != null) {
      update.set(ProductDO::getRate, rate);
    }
    tx.executeWithoutResult(s -> products.update(null, update));
    return List.of();
  }
}
