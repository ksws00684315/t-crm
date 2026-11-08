package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AddItemRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AddItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AttributeGroup;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AttributeValue;
import com.tcrm.admin.integration.api.WeidianBusinessApi.DeleteItemRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponseBlock;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemImage;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemSku;
import com.tcrm.admin.integration.api.WeidianBusinessApi.SaveItemDetailBlock;
import com.tcrm.admin.integration.api.WeidianBusinessApi.SaveItemDetailRequest;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.CopyDailyQuotaApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Weidian-backed {@link WeidianItemCopyApi} (task 17b / ADR-019). No markup; no business retry. */
@Service
public class WeidianItemCopyService implements WeidianItemCopyApi {
  private static final Logger LOG = LoggerFactory.getLogger(WeidianItemCopyService.class);

  /** Operator-facing warning when addItem succeeded but saveItemDetail failed. */
  static final String DETAIL_SAVE_WARNING = "商品已复制，详情保存失败，请在微店后台补充详情";

  static final String SOURCE_INCOMPLETE = "来源商品信息不完整";

  /** Weidian reported success for vdian.item.add but no item id could be read from the result. */
  static final String ITEM_ID_MISSING = "微店已返回成功但未识别新商品 ID，商品可能已在微店创建，请在微店后台核对";

  private final WeidianBusinessApi weidian;
  private final WeidianShopAuthMapper auths;
  private final ShopAccessApi shops;
  private final CopyDailyQuotaApi dailyQuota;

  public WeidianItemCopyService(
      WeidianBusinessApi weidian,
      WeidianShopAuthMapper auths,
      ShopAccessApi shops,
      CopyDailyQuotaApi dailyQuota) {
    this.weidian = weidian;
    this.auths = auths;
    this.shops = shops;
    this.dailyQuota = dailyQuota;
  }

  @Override
  public CopyResult copy(long sourceShopId, String sourceItemId, long targetShopId) {
    ItemResponse item = weidian.item(sourceShopId, new ItemRequest(sourceItemId, null, null));
    AddItemRequest add = buildAdd(item);
    Long sourceIdLong = parseLong(sourceItemId);
    ItemDetailResponse detail =
        sourceIdLong == null
            ? new ItemDetailResponse(List.of(), null)
            : weidian.itemDetail(sourceShopId, new ItemDetailRequest(sourceIdLong));
    // Task 140: every vdian.item.add call counts against the company's daily allowance.
    if (!dailyQuota.tryAcquire()) {
      throw new BusinessException(ErrorCode.COPY_DAILY_QUOTA_EXCEEDED);
    }
    AddItemResponse created = weidian.addItem(targetShopId, add);
    if (created == null || created.itemId() == null) {
      logItemIdMissing(targetShopId, created);
      throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED, ITEM_ID_MISSING, null);
    }
    String newId = Long.toString(created.itemId());
    List<SaveItemDetailBlock> blocks = toSaveBlocks(detail);
    if (blocks.isEmpty()) {
      return new CopyResult(newId, null);
    }
    try {
      weidian.saveItemDetail(targetShopId, new SaveItemDetailRequest(blocks));
      return new CopyResult(newId, null);
    } catch (RuntimeException e) {
      LOG.warn(
          "Weidian item {} created in shop {} but detail save failed: {}",
          newId,
          targetShopId,
          safeReason(e));
      return new CopyResult(newId, DETAIL_SAVE_WARNING);
    }
  }

  @Override
  public Optional<ProbedItem> probe(long shopId, String weidianItemId) {
    try {
      ItemResponse item = weidian.item(shopId, new ItemRequest(weidianItemId, null, null));
      if (item == null || item.itemId() == null) {
        return Optional.empty();
      }
      List<ItemImage> images = images(item.raw());
      String image = images.isEmpty() ? null : httpsUrl(images.getFirst().url());
      return Optional.of(
          new ProbedItem(
              item.itemId(),
              item.itemName(),
              itemPrice(item),
              image,
              sellerConfirmed(shopId, item.sellerId())));
    } catch (WeidianApiException e) {
      if (isItemNotFound(e)) {
        return Optional.empty();
      }
      throw new BusinessException(
          ErrorCode.WEIDIAN_API_FAILED,
          "微店接口调用失败" + (e.statusCode() >= 0 ? "：" + e.statusCode() : ""),
          null);
    } catch (WeidianTransportException e) {
      throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED, "微店接口调用失败", null);
    }
  }

  /** True only when both ids are known and equal (task 137). */
  private boolean sellerConfirmed(long shopId, String sellerId) {
    if (sellerId == null || sellerId.isBlank()) {
      return false;
    }
    ShopAccessDTO shop = shops.findShop(shopId);
    return shop != null && sellerId.equals(shop.weidianShopId());
  }

  @Override
  public boolean isShopAuthorized(long shopId) {
    Long count =
        auths.selectCount(
            new LambdaQueryWrapper<WeidianShopAuthDO>()
                .eq(WeidianShopAuthDO::getShopId, shopId)
                .eq(WeidianShopAuthDO::getStatus, WeidianAuthStatus.AUTHORIZED.name()));
    return count != null && count > 0;
  }

  /** Visible for unit tests. Rejects defaults that would create zero-price catalogue items. */
  static AddItemRequest buildAdd(ItemResponse item) {
    if (item == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED, SOURCE_INCOMPLETE, null);
    }
    JsonNode raw = item.raw();
    String name = firstNonBlank(item.itemName(), text(raw, "item_name"));
    BigDecimal price = itemPrice(item);
    if (name == null || price == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED, SOURCE_INCOMPLETE, null);
    }
    Integer stock = item.stock() != null ? item.stock() : intVal(raw, "stock");
    List<ItemImage> images = images(raw);
    List<ItemSku> skus = skus(raw);
    // Weidian builds each SKU title from attrList + the SKU's attrIds; without them vdian.item.add
    // fails with 200056 "SKU标题不能为空" (task 130). The source attribute ids belong to the
    // source shop, so they are sent as temporary attributes, which the target shop accepts as-is.
    List<AttributeGroup> attributes = attributes(raw);
    return new AddItemRequest(
        attributes.isEmpty() ? null : attributes,
        attributes.isEmpty() ? null : Boolean.TRUE,
        text(raw, "cate_ids"),
        stock,
        decimal(raw, "crossedPrice"),
        price,
        text(raw, "weight"),
        decimal(raw, "minPrice"),
        decimal(raw, "maxPrice"),
        decimal(raw, "costPrice"),
        null,
        null,
        null,
        null,
        images.isEmpty() ? null : images,
        text(raw, "item_comment"),
        null,
        null,
        name,
        1,
        // Weidian rejects vdian.item.add when the merchant product code already exists in the
        // shop. A copy carries the source product's code, so copying inside the same shop (or
        // copying the same source twice) is refused. Owner decision 2026-11-07: send no merchant
        // code at all; copied items keep none (task 127).
        null,
        null,
        null,
        skus.isEmpty() ? null : skus);
  }

  /**
   * True when Weidian explicitly says the item is missing or not owned by this shop. Unknown codes
   * must not be treated as empty — they fail the copy item instead.
   *
   * <p>The numeric codes {@code 10003}/{@code 20003}/{@code 21001} and the Chinese/English keywords
   * below are conjectured from API docs and observed messages; they must be verified against a real
   * Weidian account before M0.
   */
  @Override
  public DeleteResult deleteItem(long shopId, String weidianItemId) {
    if (weidianItemId == null || weidianItemId.isBlank()) {
      return new DeleteResult(false, "微店商品 ID 为空");
    }
    try {
      weidian.deleteItem(shopId, new DeleteItemRequest(weidianItemId.strip()));
      return new DeleteResult(true, null);
    } catch (WeidianApiException e) {
      if (isItemNotFound(e)) {
        return new DeleteResult(true, null);
      }
      return new DeleteResult(false, chineseFailReason(e));
    } catch (RuntimeException e) {
      return new DeleteResult(false, chineseFailReason(e));
    }
  }

  /** Operator-facing Chinese reason without tokens or secrets. */
  static String chineseFailReason(Throwable e) {
    if (e instanceof WeidianApiException api) {
      return "微店删除失败（错误码 " + api.statusCode() + "）";
    }
    return "微店删除失败";
  }

  static boolean isItemNotFound(WeidianApiException error) {
    if (error == null) {
      return false;
    }
    String msg = error.getMessage();
    if (msg != null) {
      String lower = msg.toLowerCase(Locale.ROOT);
      if (lower.contains("不存在")
          || lower.contains("不属于")
          || lower.contains("not exist")
          || lower.contains("not found")
          || lower.contains("does not belong")
          || lower.contains("no such item")) {
        return true;
      }
    }
    // Conjectured item-missing codes for vdian.item.get — verify with a real account at M0.
    int code = error.statusCode();
    return code == 10003 || code == 20003 || code == 21001;
  }

  /**
   * Logs only the field names of Weidian's result object (plus the JSON type of a nested {@code
   * result} child), or only the JSON type when the result is not an object; values may contain shop
   * data.
   */
  private static void logItemIdMissing(long targetShopId, AddItemResponse created) {
    JsonNode result = created == null ? null : created.raw();
    String shape;
    if (result != null && result.isObject()) {
      List<String> fields = new ArrayList<>();
      fields.addAll(result.propertyNames());
      shape = "result fields=" + fields;
      JsonNode inner = result.get("result");
      if (inner != null) {
        shape += ", inner result type=" + inner.getNodeType();
      }
    } else {
      shape = "result type=" + (result == null ? "null" : result.getNodeType());
    }
    LOG.warn(
        "Weidian vdian.item.add returned success without a recognised item id in shop {}; {}",
        targetShopId,
        shape);
  }

  private static String safeReason(Throwable e) {
    if (e instanceof WeidianApiException api) {
      return "Weidian error " + api.statusCode();
    }
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) {
      return e.getClass().getSimpleName();
    }
    String lower = msg.toLowerCase(Locale.ROOT);
    if (lower.contains("token") || lower.contains("secret")) {
      return e.getClass().getSimpleName();
    }
    return msg.length() > 120 ? msg.substring(0, 120) : msg;
  }

  private static List<SaveItemDetailBlock> toSaveBlocks(ItemDetailResponse detail) {
    if (detail == null || detail.detailContent() == null || detail.detailContent().isEmpty()) {
      return List.of();
    }
    List<SaveItemDetailBlock> out = new ArrayList<>();
    int pos = 1;
    for (ItemDetailResponseBlock block : detail.detailContent()) {
      if (block == null) {
        continue;
      }
      Integer position = block.position() != null ? block.position() : pos;
      out.add(
          new SaveItemDetailBlock(
              block.type(),
              block.url(),
              block.videoType(),
              block.text(),
              block.faceUrl(),
              position));
      pos++;
    }
    return out;
  }

  /** Weidian image addresses come as http://; product images must be https (task 131). */
  private static String httpsUrl(String url) {
    return url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
  }

  private static List<ItemImage> images(JsonNode raw) {
    if (raw == null) {
      return List.of();
    }
    JsonNode node = raw.get("imgs");
    if (node == null || !node.isArray()) {
      node = raw.get("images");
    }
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<ItemImage> out = new ArrayList<>();
    for (JsonNode img : node) {
      String url = text(img, "url");
      if (url == null) {
        url = img.isTextual() ? img.asText() : null;
      }
      if (url != null && !url.isBlank()) {
        out.add(new ItemImage(text(img, "title"), url));
      }
    }
    return out;
  }

  private static List<ItemSku> skus(JsonNode raw) {
    if (raw == null) {
      return List.of();
    }
    JsonNode node = raw.get("skus");
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<ItemSku> out = new ArrayList<>();
    for (JsonNode sku : node) {
      out.add(
          new ItemSku(
              attrIds(text(sku, "attr_ids")),
              decimal(sku, "costPrice"),
              // Same rule as the item-level code: the source SKU's merchant code would already
              // exist in the shop (task 127).
              null,
              decimal(sku, "price"),
              decimal(sku, "minPrice"),
              decimal(sku, "maxPrice"),
              intVal(sku, "stock")));
    }
    return out;
  }

  private static List<AttributeGroup> attributes(JsonNode raw) {
    JsonNode node = raw == null ? null : raw.get("attrList");
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<AttributeGroup> out = new ArrayList<>();
    for (JsonNode group : node) {
      List<AttributeValue> values = new ArrayList<>();
      JsonNode valueNodes = group.get("attrValues");
      if (valueNodes != null && valueNodes.isArray()) {
        for (JsonNode value : valueNodes) {
          values.add(
              new AttributeValue(
                  parseLong(text(value, "attrId")), text(value, "attrValue"), text(value, "img")));
        }
      }
      out.add(new AttributeGroup(text(group, "attrTitle"), values));
    }
    return out;
  }

  /** Source SKUs carry their attribute ids as one string, e.g. {@code "764651-764647"}. */
  private static List<Long> attrIds(String joined) {
    if (joined == null) {
      return null;
    }
    List<Long> out = new ArrayList<>();
    for (String part : joined.split("-")) {
      Long id = parseLong(part.trim());
      if (id != null) {
        out.add(id);
      }
    }
    return out.isEmpty() ? null : out;
  }

  private static String text(JsonNode node, String field) {
    if (node == null || field == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    String s = text(node, field);
    if (s == null) {
      return null;
    }
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer intVal(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    if (v.isIntegralNumber()) {
      return v.asInt();
    }
    try {
      return Integer.valueOf(v.asText());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  /**
   * Item-level price, or the lowest SKU price when the item has none: a multi-spec item whose SKUs
   * differ in price returns no single item price (task 132).
   */
  private static BigDecimal itemPrice(ItemResponse item) {
    BigDecimal price = firstPrice(item.price(), decimal(item.raw(), "price"));
    if (price != null) {
      return price;
    }
    JsonNode skus = item.raw() == null ? null : item.raw().get("skus");
    if (skus == null || !skus.isArray()) {
      return null;
    }
    BigDecimal lowest = null;
    for (JsonNode sku : skus) {
      BigDecimal skuPrice = decimal(sku, "price");
      if (skuPrice != null && (lowest == null || skuPrice.compareTo(lowest) < 0)) {
        lowest = skuPrice;
      }
    }
    return lowest;
  }

  private static BigDecimal firstPrice(BigDecimal a, BigDecimal b) {
    if (a != null) {
      return a;
    }
    return b;
  }
}
