package com.tcrm.admin.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.notification.mapper.NotificationMapper;
import com.tcrm.admin.notification.model.NotificationDO;
import com.tcrm.admin.notification.model.NotificationType;
import com.tcrm.admin.notification.service.NotificationService;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.EnabledSuperAdminApi;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

class NotificationControllerTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final NotificationMapper notifications = mock(NotificationMapper.class);
  private final NotificationService service =
      new NotificationService(
          notifications,
          mock(KolApi.class),
          mock(EnabledSuperAdminApi.class),
          current,
          TestTransactions.template(),
          Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC));
  private final MockMvc mvc = AdminMvcSupport.build(current, new NotificationController(service));

  @BeforeAll
  static void table() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationDO.class);
  }

  @Test
  void shouldReturnTheCallersUnreadCount() throws Exception {
    when(notifications.selectCount(any())).thenReturn(4L);
    mvc.perform(
            get("/admin-api/notification/notifications/unread-count")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.count").value("4"));
  }

  @Test
  void shouldReturnNotFound_whenMarkingSomeoneElsesMessage() throws Exception {
    when(notifications.selectOne(any())).thenReturn(null);
    mvc.perform(
            put("/admin-api/notification/notifications/99/read")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }

  @Test
  void shouldRejectAPlatformAdministrator() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.PLATFORM_ADMIN, DataScope.ALL);
    mvc.perform(
            get("/admin-api/notification/notifications/unread-count")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldPageTheCallersMessages() throws Exception {
    when(notifications.selectPage(any(), any()))
        .thenAnswer(
            invocation -> {
              Page<NotificationDO> page = invocation.getArgument(0);
              NotificationDO row = new NotificationDO();
              row.setId(5L);
              row.setUserId(8L);
              row.setType(NotificationType.KOLS_UNASSIGNED);
              row.setParams("{\"bdName\":\"Blake\",\"count\":2}");
              row.setBizType("STAFF");
              row.setBizId(5L);
              row.setCreateTime(Instant.parse("2026-10-17T00:00:00Z"));
              page.setRecords(List.of(row));
              page.setTotal(1);
              return page;
            });
    mvc.perform(
            get("/admin-api/notification/notifications")
                .param("unread", "true")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value("5"))
        .andExpect(jsonPath("$.data.items[0].params.bdName").value("Blake"))
        .andExpect(jsonPath("$.data.items[0].params.count").value(2));
  }

  @Test
  void shouldNotAuditTheseEndpoints() throws Exception {
    assertThat(NotificationController.class.getAnnotation(AuditLog.class)).isNull();
    for (Method method : NotificationController.class.getDeclaredMethods()) {
      if (method.isAnnotationPresent(GetMapping.class)
          || method.isAnnotationPresent(PutMapping.class)) {
        assertThat(method.getAnnotation(AuditLog.class)).isNull();
      }
    }
  }

  private String token() {
    String token = AdminMvcSupport.actAs(current, 8L, Role.STAFF, DataScope.ALL);
    when(current.user()).thenReturn(account(8L, Role.STAFF, 2L));
    return token;
  }

  private static AccountDTO account(long id, Role role, Long tenantId) {
    return new AccountDTO(
        id,
        "user" + id,
        null,
        "User",
        null,
        role,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        tenantId,
        "Company",
        true);
  }
}
