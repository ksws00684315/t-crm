package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.mapper.ProductMapper;
import org.junit.jupiter.api.Test;

class ProductCodeGeneratorTest {
  @Test
  void shouldSkipTakenCodes() {
    ProductMapper products = mock(ProductMapper.class);
    when(products.nextCodeNumber()).thenReturn(1L, 2L, 3L);
    when(products.existsCode("P000001")).thenReturn(true);
    when(products.existsCode("P000002")).thenReturn(true);
    when(products.existsCode("P000003")).thenReturn(false);
    assertThat(new ProductCodeGenerator(products).next()).isEqualTo("P000003");
  }
}
