package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductRateDTO;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.ProductDO;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Product read facade for other modules (task 40). Soft-deleted products stay visible: order
 * history must still resolve codes and images after a product is removed.
 */
@Service
public class ProductAccessService implements ProductAccessApi {
  private final ProductMapper products;

  public ProductAccessService(ProductMapper products) {
    this.products = products;
  }

  @Override
  public ProductRefDTO getProduct(Long productId) {
    if (productId == null) {
      return null;
    }
    var row =
        products.selectOne(
            new LambdaQueryWrapper<ProductDO>().eq(ProductDO::getId, productId).last("LIMIT 1"));
    return row == null ? null : toDto(row);
  }

  @Override
  public List<ProductRefDTO> listProducts(Collection<Long> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return List.of();
    }
    return products
        .selectList(new LambdaQueryWrapper<ProductDO>().in(ProductDO::getId, productIds))
        .stream()
        .map(ProductAccessService::toDto)
        .toList();
  }

  @Override
  public List<ProductRateDTO> listRates(Collection<Long> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return List.of();
    }
    return products
        .selectList(
            new LambdaQueryWrapper<ProductDO>()
                .select(ProductDO::getId, ProductDO::getRate)
                .in(ProductDO::getId, productIds)
                .isNotNull(ProductDO::getRate))
        .stream()
        .map(row -> new ProductRateDTO(row.getId(), row.getRate()))
        .toList();
  }

  private static ProductRefDTO toDto(ProductDO row) {
    return new ProductRefDTO(
        row.getId(), row.getProductCode(), row.getName(), row.getImageUrl(), row.getStatus());
  }
}
