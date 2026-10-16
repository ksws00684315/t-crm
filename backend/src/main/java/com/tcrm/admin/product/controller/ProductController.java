package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.service.ProductService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Original products (task 05 §4.2, §5). Read, edit and status each declare their own permission
 * code.
 */
@RestController
@RequestMapping("/admin-api/product/products")
public class ProductController {
  private final ProductService products;

  public ProductController(ProductService products) {
    this.products = products;
  }

  @GetMapping
  @SaCheckPermission("product:view")
  public R<PageResult<ProductVO>> list(@Valid @ModelAttribute ProductQuery q) {
    return R.ok(products.page(q.toFilter()).map(ProductVO::from));
  }

  @GetMapping("/{id}")
  @SaCheckPermission("product:view")
  public R<ProductVO> get(@PathVariable @Min(1) Long id) {
    return R.ok(ProductVO.from(products.get(id)));
  }

  @PutMapping("/{id}")
  @SaCheckPermission("product:edit")
  @AuditLog(module = "PRODUCT", action = "Edit product")
  public R<Void> edit(@PathVariable @Min(1) Long id, @Valid @RequestBody EditProductRequest r) {
    products.update(id, r.toCommand());
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @SaCheckPermission("product:status")
  @AuditLog(module = "PRODUCT", action = "Change product status")
  public R<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody ProductStatusRequest r) {
    products.updateStatus(id, r.status());
    return R.ok(null);
  }
}
