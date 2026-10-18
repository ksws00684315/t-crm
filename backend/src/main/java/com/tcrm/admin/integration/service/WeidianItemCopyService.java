package com.tcrm.admin.integration.service;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AddItemRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AddItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponseBlock;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemImage;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemSku;
import com.tcrm.admin.integration.api.WeidianBusinessApi.SaveItemDetailBlock;
import com.tcrm.admin.integration.api.WeidianBusinessApi.SaveItemDetailRequest;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Weidian-backed {@link WeidianItemCopyApi} (task 17b / ADR-019). No markup; no business retry. */
@Service
public class WeidianItemCopyService implements WeidianItemCopyApi {
  private final WeidianBusinessApi weidian;

  public WeidianItemCopyService(WeidianBusinessApi weidian) {
    this.weidian = weidian;
  }

  @Override
  public String copy(long sourceShopId, String sourceItemId, long targetShopId) {
    ItemResponse item = weidian.item(sourceShopId, new ItemRequest(sourceItemId, null, null));
    Long sourceIdLong = parseLong(sourceItemId);
    ItemDetailResponse detail =
        sourceIdLong == null
            ? new ItemDetailResponse(List.of(), null)
            : weidian.itemDetail(sourceShopId, new ItemDetailRequest(sourceIdLong));
    AddItemResponse created = weidian.addItem(targetShopId, buildAdd(item));
    if (created == null || created.itemId() == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED);
    }
    List<SaveItemDetailBlock> blocks = toSaveBlocks(detail);
    if (!blocks.isEmpty()) {
      weidian.saveItemDetail(targetShopId, new SaveItemDetailRequest(blocks));
    }
    return Long.toString(created.itemId());
  }

  @Override
  public Optional<ProbedItem> probe(long shopId, String weidianItemId) {
    try {
      ItemResponse item = weidian.item(shopId, new ItemRequest(weidianItemId, null, null));
      if (item == null || item.itemId() == null) {
        return Optional.empty();
      }
      return Optional.of(new ProbedItem(item.itemId(), item.itemName(), item.price()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static AddItemRequest buildAdd(ItemResponse item) {
    JsonNode raw = item.raw();
    String name = firstNonBlank(item.itemName(), text(raw, "item_name"), "copied-item");
    BigDecimal price = firstPrice(item.price(), decimal(raw, "price"), BigDecimal.ZERO);
    Integer stock = item.stock() != null ? item.stock() : intVal(raw, "stock");
    List<ItemImage> images = images(raw);
    List<ItemSku> skus = skus(raw);
    String merchantCode = text(raw, "merchant_code");
    if (merchantCode == null) {
      merchantCode = text(raw, "merchantCode");
    }
    return new AddItemRequest(
        null,
        null,
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
        merchantCode,
        null,
        null,
        skus.isEmpty() ? null : skus);
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
              null,
              decimal(sku, "costPrice"),
              text(sku, "merchantCode"),
              decimal(sku, "price"),
              decimal(sku, "minPrice"),
              decimal(sku, "maxPrice"),
              intVal(sku, "stock")));
    }
    return out;
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

  private static BigDecimal firstPrice(BigDecimal a, BigDecimal b, BigDecimal fallback) {
    if (a != null) {
      return a;
    }
    if (b != null) {
      return b;
    }
    return fallback;
  }
}
