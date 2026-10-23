package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.WithdrawalDetailService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class WithdrawalDetailControllerTest {
  private final WithdrawalDetailService service = mock(WithdrawalDetailService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new WithdrawalDetailController(service, current));

  private String token(Set<String> permissions) {
    return AdminMvcSupport.actAs(current, 4L, Role.STAFF, DataScope.OWN, permissions);
  }

  @Test
  void returnsUniformEnvelopeAndDefaultTenOrderPage() throws Exception {
    when(service.orders(any(), eq(1L), eq(1), eq(10)))
        .thenReturn(new PageResult<>(List.of(), 11, 1, 10));
    mvc.perform(
            get("/admin-api/commission/withdrawals/1/orders")
                .header(AdminMvcSupport.TOKEN_HEADER, token(Set.of("commission:view"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.total").value("11"))
        .andExpect(jsonPath("$.data.size").value(10));
  }

  @Test
  void validatesIdentifiersAndPagination() throws Exception {
    var token = token(Set.of("withdrawal:review"));
    for (String path :
        List.of("/0", "/1/orders?page=0", "/1/orders?size=101", "/1/orders?page=10001")) {
      mvc.perform(
              get("/admin-api/commission/withdrawals" + path)
                  .header(AdminMvcSupport.TOKEN_HEADER, token))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    }
  }

  @Test
  void prepayPermissionDoesNotGrantWithdrawalAccess() throws Exception {
    mvc.perform(
            get("/admin-api/commission/withdrawals/1")
                .header(AdminMvcSupport.TOKEN_HEADER, token(Set.of("prepay:review"))))
        .andExpect(status().isForbidden());
  }
}
