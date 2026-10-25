package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionRuleVersionMapper;
import com.tcrm.admin.commission.model.CommissionRuleVersionDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the settlement config service (requirement 11.03, ADR-032 §4). */
class CommissionRuleConfigServiceTest {
  private static final long TENANT = 7L;
  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

  private final CommissionRuleVersionMapper versions = mock(CommissionRuleVersionMapper.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AccountDTO superAdmin = superAdmin();
  private final CommissionRuleConfigService service =
      new CommissionRuleConfigService(versions, audit, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void prepare() {
    when(versions.insert(any(CommissionRuleVersionDO.class))).thenReturn(1);
  }

  @Test
  void shouldReturnDefaultsWithVersionZeroWhenNothingSaved() {
    when(versions.findLatest()).thenReturn(null);
    var config = TenantScope.callCompany(TENANT, service::current);
    assertThat(config.version()).isZero();
    assertThat(config.minimumCny()).isEqualByComparingTo("200.00");
    assertThat(config.weidianDays()).isEqualTo(7);
    assertThat(config.weidianBasis()).isEqualTo("SETTLE_TIME");
    assertThat(config.savedAt()).isNull();
  }

  @Test
  void shouldReturnLatestSavedVersion() {
    when(versions.findLatest())
        .thenReturn(
            version(3, "150.00", "{\"WEIDIAN\":{\"basis\":\"COMPLETED_PLUS_DAYS\",\"days\":14}}"));
    var config = TenantScope.callCompany(TENANT, service::current);
    assertThat(config.version()).isEqualTo(3);
    assertThat(config.minimumCny()).isEqualByComparingTo("150.00");
    assertThat(config.weidianBasis()).isEqualTo("COMPLETED_PLUS_DAYS");
    assertThat(config.weidianDays()).isEqualTo(14);
  }

  @Test
  void shouldSaveNextVersionWithAudit() {
    when(versions.findLatest()).thenReturn(version(2, "200.00", "{\"WEIDIAN\":{\"days\":7}}"));
    var saved = service.save(superAdmin, 2, new BigDecimal("180.50"), 10);
    assertThat(saved.version()).isEqualTo(3);
    assertThat(saved.minimumCny()).isEqualByComparingTo("180.50");
    assertThat(saved.weidianDays()).isEqualTo(10);
    verify(audit)
        .recordTenantWriteOperation(
            anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void shouldRejectStaleExpectedVersion() {
    when(versions.findLatest()).thenReturn(version(2, "200.00", "{\"WEIDIAN\":{\"days\":7}}"));
    assertThatThrownBy(() -> service.save(superAdmin, 1, new BigDecimal("180.00"), 10))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMISSION_CONFIG_VERSION_CONFLICT);
  }

  @Test
  void shouldAcceptVersionZeroWhenNothingSaved() {
    when(versions.findLatest()).thenReturn(null);
    var saved = service.save(superAdmin, 0, new BigDecimal("99.99"), 0);
    assertThat(saved.version()).isEqualTo(1);
    assertThat(saved.weidianDays()).isZero();
  }

  @Test
  void shouldRejectInvalidMinimumAndDays() {
    assertThatThrownBy(() -> service.save(superAdmin, 0, null, 7))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.save(superAdmin, 0, new BigDecimal("-1"), 7))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.save(superAdmin, 0, new BigDecimal("1.234"), 7))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.save(superAdmin, 0, new BigDecimal("100"), 366))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.save(superAdmin, 0, new BigDecimal("100"), -1))
        .isInstanceOf(BusinessException.class);
  }

  private static CommissionRuleVersionDO version(int no, String minimum, String rules) {
    CommissionRuleVersionDO row = new CommissionRuleVersionDO();
    row.setId(900L + no);
    row.setVersionNo(no);
    row.setCurrency("CNY");
    row.setMinimumWithdrawalCny(new BigDecimal(minimum));
    row.setChannelRules(rules);
    row.setCreateTime(NOW.minusSeconds(3600));
    return row;
  }

  private static AccountDTO superAdmin() {
    return new AccountDTO(
        5L,
        "t62_admin",
        null,
        "Task 62",
        "t62@example.test",
        Role.SUPER_ADMIN,
        null,
        1,
        NOW,
        NOW,
        0,
        TENANT,
        "Task 62 Co",
        true,
        java.util.Set.of(),
        null);
  }
}
