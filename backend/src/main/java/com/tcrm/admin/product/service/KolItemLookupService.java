package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemLinkRow;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.shared.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Kol item mapping facade for other modules (task 06 link index, link list, click attribution). */
@Service
public class KolItemLookupService implements KolItemApi {
  private final KolItemMapper items;
  private final ProductMapper products;

  public KolItemLookupService(KolItemMapper items, ProductMapper products) {
    this.items = items;
    this.products = products;
  }

  @Override
  public List<KolItemLinkDTO> listCurrentLinksByKol(Long kolId) {
    return toDtos(items.listCurrentLinksByKol(kolId));
  }

  @Override
  public List<KolItemLinkDTO> listCurrentLinksByKolAndShop(Long kolId, Long shopId) {
    return toDtos(items.listCurrentLinksByKolAndShop(kolId, shopId));
  }

  @Override
  public List<KolItemLinkDTO> listCurrentLinksByProduct(Long productId) {
    return toDtos(items.listCurrentLinksByProduct(productId));
  }

  @Override
  public List<Long> pageKolIdsWithCurrentProduct(Long productId, Long afterKolId, int limit) {
    return items.pageKolIdsWithCurrentProduct(productId, afterKolId, limit);
  }

  @Override
  public List<Long> pageKolIdsWithCurrentShop(Long shopId, Long afterKolId, int limit) {
    return items.pageKolIdsWithCurrentShop(shopId, afterKolId, limit);
  }

  @Override
  public KolItemLinkDTO getCurrentLink(Long kolId, Long productId) {
    KolItemLinkRow row = items.getCurrentLink(kolId, productId);
    return row == null ? null : toDto(row);
  }

  @Override
  public PageResult<KolItemLinkDTO> pageCurrentLinksByKol(
      Long kolId, String productCode, int page, int size) {
    String code =
        productCode == null || productCode.isBlank()
            ? null
            : productCode.strip().toUpperCase(Locale.ROOT);
    var result = items.pageCurrentLinksByKol(new Page<>(page, size), kolId, code);
    return new PageResult<>(toDtos(result.getRecords()), result.getTotal(), page, size);
  }

  @Override
  public String getProductCode(Long productId) {
    ProductDO product = products.selectById(productId);
    return product == null ? null : product.getProductCode();
  }

  @Override
  public List<KolItemRefDTO> listRefsByIds(Collection<Long> kolItemIds) {
    if (kolItemIds == null || kolItemIds.isEmpty()) {
      return List.of();
    }
    return items
        .selectList(
            new LambdaQueryWrapper<KolItemDO>()
                .select(KolItemDO::getId, KolItemDO::getKolId, KolItemDO::getProductId)
                .in(KolItemDO::getId, kolItemIds))
        .stream()
        .map(row -> new KolItemRefDTO(row.getId(), row.getKolId(), row.getProductId()))
        .toList();
  }

  private static List<KolItemLinkDTO> toDtos(List<KolItemLinkRow> rows) {
    return rows.stream().map(KolItemLookupService::toDto).toList();
  }

  static KolItemLinkDTO toDto(KolItemLinkRow row) {
    return new KolItemLinkDTO(
        row.getKolItemId(),
        row.getKolId(),
        row.getProductId(),
        row.getProductCode(),
        row.getProductName(),
        row.getShopId(),
        row.getWeidianItemId(),
        Integer.valueOf(1).equals(row.getProductStatus()),
        Integer.valueOf(1).equals(row.getShopStatus()));
  }
}
