package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.CommissionRuleConfigService;
import com.tcrm.admin.commission.service.CommissionRuleConfigService.CurrentConfig;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SettlementConfigControllerTest {
  private final CommissionRuleConfigService configs = mock(CommissionRuleConfigService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new SettlementConfigController(configs, current));

  @Test
  void shouldReturnCurrentConfigWithChannels() throws Exception {
    when(configs.current())
        .thenReturn(
            new CurrentConfig(
                3,
                new BigDecimal("150.00"),
                "COMPLETED_PLUS_DAYS",
                14,
                Instant.parse("2026-10-24T08:00:00Z")));
    mvc.perform(
            get("/admin-api/commission/settlement-configs/current")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currency").value("CNY"))
        .andExpect(jsonPath("$.data.minimum").value("150.00"))
        .andExpect(jsonPath("$.data.version").value(3))
        .andExpect(jsonPath("$.data.channels[0].basis").value("COMPLETED_PLUS_DAYS"))
        .andExpect(jsonPath("$.data.channels[0].days").value(14));
  }

  @Test
  void shouldSaveOnlyForSuperAdminWithVersionLock() throws Exception {
    when(configs.save(any(), eq(3), eq(new BigDecimal("180.00")), eq(10)))
        .thenReturn(
            new CurrentConfig(
                4,
                new BigDecimal("180.00"),
                "SETTLE_TIME",
                10,
                Instant.parse("2026-10-24T09:00:00Z")));
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"minimum\":\"180.00\",\"days\":10}")
                .header(
                    AdminMvcSupport.TOKEN_HEADER,
                    AdminMvcSupport.actAs(current, 5L, Role.SUPER_ADMIN, null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(4));
  }

  @Test
  void shouldRejectStaffSaveAndValidateBounds() throws Exception {
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"minimum\":\"180.00\",\"days\":10}")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"minimum\":\"180.00\",\"days\":400}")
                .header(
                    AdminMvcSupport.TOKEN_HEADER,
                    AdminMvcSupport.actAs(current, 5L, Role.SUPER_ADMIN, null)))
        .andExpect(status().isBadRequest());
  }

  private String viewerToken() {
    return AdminMvcSupport.actAs(
        current,
        9L,
        Role.STAFF,
        com.tcrm.admin.security.api.DataScope.ALL,
        java.util.Set.of("commission:view"));
  }
}
