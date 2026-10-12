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
import com.tcrm.admin.task.api.ImportReason;
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
  static final String CODE_FORMAT = "import.product.error.code-format";
  static final String CODE_DUPLICATE = "import.product.error.code-duplicate";
  static final String CODE_EXISTS = "import.product.error.code-exists";
  static final String CODE_IMMUTABLE = "import.product.error.code-immutable";
  static final String SHOP_REQUIRED = "import.product.error.shop-required";
  static final String SHOP_FORMAT = "import.product.error.shop-format";
  static final String SHOP_MISSING = "import.product.error.shop-missing";
  static final String ITEM_REQUIRED = "import.product.error.item-required";
  static final String ITEM_FORMAT = "import.product.error.item-format";
  static final String ITEM_DUPLICATE = "import.product.error.item-duplicate";
  static final String NAME_REQUIRED = "import.product.error.name-required";
  static final String NAME_LENGTH = "import.product.error.name-length";
  static final String CATEGORY_LENGTH = "import.product.error.category-length";
  static final String IMAGE_FORMAT = "import.product.error.image-format";
  static final String PRICE_REQUIRED = "import.product.error.price-required";
  static final String PRICE_FORMAT = "import.product.error.price-format";
  static final String RATE_FORMAT = "import.product.error.rate-format";
  static final String EXCLUSIVE = "import.product.error.exclusive";
  static final String CONFLICT = "import.product.error.conflict";

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
  public List<ImportReason> process(List<String> row) {
    List<ImportReason> errors = new ArrayList<>();
    String codeText = CODE.of(row).toUpperCase(Locale.ROOT);
    String code = codeText.isEmpty() ? null : codeText;
    if (code != null) {
      if (!ProductRules.isCode(code)) {
        errors.add(ImportReason.of(CODE_FORMAT));
      } else if (!seenCodes.add(code)) {
        errors.add(ImportReason.of(CODE_DUPLICATE));
      }
    }
    String shopWeidian = SHOP_ID.of(row);
    ShopDO shop = null;
    if (shopWeidian.isEmpty()) {
      errors.add(ImportReason.of(SHOP_REQUIRED));
    } else if (!ProductRules.isWeidianId(shopWeidian)) {
      errors.add(ImportReason.of(SHOP_FORMAT));
    } else {
      shop = shops.findEnabledByWeidianId(shopWeidian);
      if (shop == null) {
        errors.add(ImportReason.of(SHOP_MISSING));
      }
    }
    String itemId = WEIDIAN_ITEM_ID.of(row);
    if (itemId.isEmpty()) {
      errors.add(ImportReason.of(ITEM_REQUIRED));
    } else if (!ProductRules.isWeidianId(itemId)) {
      errors.add(ImportReason.of(ITEM_FORMAT));
    } else if (shop != null && !seenShopItems.add(shop.getId() + ":" + itemId)) {
      errors.add(ImportReason.of(ITEM_DUPLICATE));
    }
    String name = NAME.of(row);
    String category = CATEGORY.of(row);
    String image = IMAGE_URL.of(row);
    String priceText = PRICE.of(row);
    String rateText = RATE.of(row);
    if (!category.isEmpty() && ProductRules.length(category) > ProductRules.MAX_CATEGORY) {
      errors.add(ImportReason.of(CATEGORY_LENGTH));
    }
    if (!image.isEmpty()
        && (ProductRules.length(image) > ProductRules.MAX_IMAGE_URL
            || !ProductRules.isHttpsUrl(image))) {
      errors.add(ImportReason.of(IMAGE_FORMAT));
    }
    BigDecimal price = null;
    if (!priceText.isEmpty()) {
      price = ProductRules.parsePrice(priceText);
      if (price == null) {
        errors.add(ImportReason.of(PRICE_FORMAT));
      }
    }
    BigDecimal rate = null;
    if (!rateText.isEmpty()) {
      rate = ProductRules.percentToRate(rateText);
      if (rate == null) {
        errors.add(ImportReason.of(RATE_FORMAT));
      }
    }
    if (!name.isEmpty() && ProductRules.length(name) > ProductRules.MAX_NAME) {
      errors.add(ImportReason.of(NAME_LENGTH));
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
            case PRODUCT_CODE_EXISTS -> ImportReason.of(CODE_EXISTS);
            case PRODUCT_CODE_IMMUTABLE -> ImportReason.of(CODE_IMMUTABLE);
            case PRODUCT_IS_EXCLUSIVE_ITEM -> ImportReason.of(EXCLUSIVE);
            case PRODUCT_WEIDIAN_ID_EXISTS -> ImportReason.of(ITEM_DUPLICATE);
            case COMMON_CONFLICT -> ImportReason.of(CONFLICT);
            default -> throw e;
          });
    }
  }

  private List<ImportReason> insert(
      ShopDO shop,
      String itemId,
      String code,
      String name,
      String category,
      String image,
      BigDecimal price,
      BigDecimal rate,
      List<ImportReason> errors) {
    if (name.isEmpty()) {
      errors.add(ImportReason.of(NAME_REQUIRED));
    }
    if (price == null) {
      errors.add(ImportReason.of(PRICE_REQUIRED));
    }
    if (code != null && products.existsCode(code)) {
      errors.add(ImportReason.of(CODE_EXISTS));
    }
    if (kolItems.existsCurrentShopItem(shop.getId(), itemId)) {
      errors.add(ImportReason.of(EXCLUSIVE));
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

  private List<ImportReason> update(
      ProductDO existing,
      String code,
      String name,
      String category,
      String image,
      BigDecimal price,
      BigDecimal rate,
      List<ImportReason> errors) {
    if (code != null && !code.equals(existing.getProductCode())) {
      errors.add(ImportReason.of(CODE_IMMUTABLE));
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
