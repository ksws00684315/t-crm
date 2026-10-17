package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.ProductDTO;
import com.tcrm.admin.product.service.ProductService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ProductControllerTest {
  private static final String BASE = "/admin-api/product/products";
  private final ProductService products = mock(ProductService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new ProductController(products));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  private static ProductDTO sample() {
    return new ProductDTO(
        1L,
        "P000001",
        2L,
        "店",
        "100",
        "2001",
        "鞋",
        "类",
        "https://a.com/x.png",
        new BigDecimal("12.50"),
        "CNY",
        new BigDecimal("0.1550"),
        1,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldList_whenAnyRoleReads(Role role, DataScope scope) throws Exception {
    when(products.page(any())).thenReturn(new PageResult<>(List.of(sample()), 1, 1, 20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].price").value("12.50"))
        .andExpect(jsonPath("$.data.items[0].priceCurrency").value("CNY"))
        .andExpect(jsonPath("$.data.items[0].rate").value("0.1550"));
  }

  @Test
  void shouldEdit_whenOperatorAll() throws Exception {
    mvc.perform(
            put(BASE + "/1")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"鞋\",\"category\":null,\"imageUrl\":null,\"price\":\"12.50\",\"rate\":null}"))
        .andExpect(status().isOk());
    verify(products).update(eq(1L), any());
  }

  @Test
  void shouldReturn403_whenStaffLacksProductEdit() throws Exception {
    String token =
        AdminMvcSupport.actAs(
            current, 1L, Role.STAFF, DataScope.ALL, java.util.Set.of("product:view"));
    mvc.perform(
            put(BASE + "/1")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"鞋\",\"price\":\"1.00\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(products);
  }

  @Test
  void shouldEdit_whenStaffOwnHasProductEdit() throws Exception {
    mvc.perform(
            put(BASE + "/1")
                .header(
                    AdminMvcSupport.TOKEN_HEADER,
                    AdminMvcSupport.actAs(
                        current, 1L, Role.STAFF, DataScope.OWN, java.util.Set.of("product:edit")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"鞋\",\"price\":\"1.00\"}"))
        .andExpect(status().isOk());
    verify(products).update(eq(1L), any());
  }
}
