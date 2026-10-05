package com.tcrm.admin.product.service;

import com.tcrm.admin.product.mapper.ProductMapper;
import org.springframework.stereotype.Component;

/**
 * Issues product codes from crm_product_code_seq (task 05 §4.2, design §8.2): P + number padded to
 * 6 digits, widening automatically past 999999; numbers whose code is already taken are skipped.
 */
@Component
public class ProductCodeGenerator {
  static final int MAX_SKIPS = 10_000;

  private final ProductMapper products;

  public ProductCodeGenerator(ProductMapper products) {
    this.products = products;
  }

  /** Returns a code that was free when checked; the unique index still guards concurrent use. */
  public String next() {
    for (int i = 0; i <= MAX_SKIPS; i++) {
      String code = ProductRules.formatCode(products.nextCodeNumber());
      if (!products.existsCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("No free product code after " + MAX_SKIPS + " attempts");
  }
}
