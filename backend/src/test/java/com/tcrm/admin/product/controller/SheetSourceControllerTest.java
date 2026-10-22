package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.product.service.SheetSourceService;
import com.tcrm.admin.shared.GlobalExceptionHandler;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Regression: parameter constraints on controllers work via Spring MVC built-in method validation
 * without class-level {@code @Validated} (see ArchitectureTest and spec 03).
 */
class SheetSourceControllerTest {
  private final SheetSourceService sources = mock(SheetSourceService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SheetSourceController(sources))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void shouldRejectOversizedPage_whenListingMissingProducts() throws Exception {
    mvc.perform(get("/admin-api/product/sheet-sources/1/missing-products?size=101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verify(sources, never()).missing(anyLong(), anyInt(), anyInt());
  }

  @Test
  void shouldAcceptLastAllowedSize_whenListingMissingProducts() throws Exception {
    when(sources.missing(1L, 1, 100)).thenReturn(new PageResult<>(List.of(), 0, 1, 100));
    mvc.perform(get("/admin-api/product/sheet-sources/1/missing-products?size=100"))
        .andExpect(status().isOk());
    verify(sources).missing(1L, 1, 100);
  }
}
