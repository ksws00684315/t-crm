package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.KolItemDetail;
import com.tcrm.admin.product.service.KolItemService;
import com.tcrm.admin.product.service.KolItemView;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class KolItemControllerTest {
  private final KolItemService items = mock(KolItemService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new KolItemController(items));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  private MockHttpServletRequestBuilder list(String token) {
    return get("/admin-api/product/kol-items").header(AdminMvcSupport.TOKEN_HEADER, token);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldList_whenAllowedRoleReads(Role role, DataScope scope) throws Exception {
    when(items.page(any())).thenReturn(new PageResult<>(List.of(sample()), 1, 1, 20));
    mvc.perform(list(as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].linkCode").value("KPL-ABCDEF123456"))
        .andExpect(jsonPath("$.data.items[0].salesQuantity").value("3"))
        .andExpect(jsonPath("$.data.items[0].payAmount").value("14.00"))
        .andExpect(jsonPath("$.data.items[0].targetShopCode").value("SHOP-100003"));
    verify(items).page(any());
  }

  @Test
  void shouldReturnDetail() throws Exception {
    when(items.detail(5L))
        .thenReturn(
            new KolItemDetail(
                sample(),
                "https://weidian.com/item.html?itemID=8001",
                1L,
                new BigDecimal("14.00"),
                new BigDecimal("3.50"),
                Instant.parse("2026-10-20T00:00:00Z"),
                "closed",
                "rejected"));
    mvc.perform(
            get("/admin-api/product/kol-items/5")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.visitCount").value("1"))
        .andExpect(jsonPath("$.data.totalPayAmount").value("14.00"))
        .andExpect(jsonPath("$.data.refundAmount").value("3.50"))
        .andExpect(
            jsonPath("$.data.sourceItemUrl").value("https://weidian.com/item.html?itemID=8001"))
        .andExpect(jsonPath("$.data.cleanupReason").value("closed"))
        .andExpect(jsonPath("$.data.cleanupFailReason").value("rejected"));
    verify(items).detail(eq(5L));
  }

  @Test
  void shouldReturn404_whenDetailIsMissing() throws Exception {
    doThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND)).when(items).detail(9L);
    mvc.perform(
            get("/admin-api/product/kol-items/9")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }

  @Test
  void shouldReturn400_whenDetailIdIsNotPositive() throws Exception {
    mvc.perform(
            get("/admin-api/product/kol-items/0")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(items);
  }

  private static KolItemView sample() {
    return new KolItemView(
        5L,
        "KPL-ABCDEF123456",
        "Hat",
        null,
        11L,
        "KOL-TAA001",
        "Alice",
        3L,
        "Shop",
        "SHOP-100003",
        "WEIDIAN",
        "9001",
        "https://weidian.com/item.html?itemID=9001",
        "8001",
        "WEIDIAN",
        "0.1500",
        3L,
        new BigDecimal("14.00"),
        "ENABLED",
        "FAILED",
        Instant.parse("2026-10-21T00:00:00Z"));
  }
}
