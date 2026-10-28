package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.system.model.SystemConfigDefinition;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SystemConfigValidationTest {
  @Test
  void shouldRegisterEveryTask34Setting_andKeepInvitationMaximumAt72Hours() {
    // 33 task-34 settings plus the two task-76 settlement pull settings.
    assertThat(SystemConfigDefinition.values()).hasSize(35);
    assertThat(SystemConfigDefinition.find("link.short-url.allowed-hosts"))
        .isEqualTo(SystemConfigDefinition.SHORT_URL_ALLOWED_HOSTS);
    assertThat(SystemConfigDefinition.find("commission.minimum-withdrawal-cny"))
        .isEqualTo(SystemConfigDefinition.MINIMUM_WITHDRAWAL_CNY);
    assertThat(SystemConfigDefinition.find("weidian.settlement.sync-interval-minutes"))
        .isEqualTo(SystemConfigDefinition.WEIDIAN_SETTLEMENT_SYNC_INTERVAL_MINUTES);
    assertThat(SystemConfigDefinition.find("weidian.settlement.backfill-hour"))
        .isEqualTo(SystemConfigDefinition.WEIDIAN_SETTLEMENT_BACKFILL_HOUR);
    assertThat(SystemConfigDefinition.list())
        .contains(SystemConfigDefinition.ORDER_SYNC_FAILURE_RETENTION_DAYS);
    assertThat(SystemConfigDefinition.KOL_INVITE_TTL_HOURS.min()).isEqualTo(1);
    assertThat(SystemConfigDefinition.KOL_INVITE_TTL_HOURS.max()).isEqualTo(72);
    assertThat(
            java.util.Arrays.stream(SystemConfigDefinition.values())
                .map(SystemConfigDefinition::key)
                .collect(Collectors.toSet()))
        .hasSize(35);
  }

  @Test
  void shouldAllowHttpsHostOnlyAndLocalhostHttpAddresses() {
    assertThat(SystemConfigService.validBaseUrl("https://example.com")).isTrue();
    assertThat(SystemConfigService.validBaseUrl("http://localhost:8832")).isTrue();
    assertThat(SystemConfigService.validBaseUrl("http://127.0.0.1:18263")).isTrue();
    assertThat(SystemConfigService.validBaseUrl("http://example.com")).isFalse();
    assertThat(SystemConfigService.validBaseUrl("https://example.com/path")).isFalse();
    assertThat(SystemConfigService.validBaseUrl("https://example.com/")).isFalse();
    assertThat(SystemConfigService.validBaseUrl("https://user@example.com")).isFalse();
  }

  @Test
  void shouldValidateIntegerRangesAndOptionalUploadAddress() {
    SystemConfigService.validate(SystemConfigDefinition.SHOP_MAX_PER_COMPANY, "1");
    SystemConfigService.validate(SystemConfigDefinition.SHOP_MAX_PER_COMPANY, "1000");
    SystemConfigService.validate(SystemConfigDefinition.ORDER_SYNC_FAILURE_RETENTION_DAYS, "0");
    SystemConfigService.validate(SystemConfigDefinition.ORDER_SYNC_FAILURE_RETENTION_DAYS, "3650");
    assertThatThrownBy(
            () ->
                SystemConfigService.validate(
                    SystemConfigDefinition.ORDER_SYNC_FAILURE_RETENTION_DAYS, "-1"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                SystemConfigService.validate(
                    SystemConfigDefinition.ORDER_SYNC_FAILURE_RETENTION_DAYS, "3651"))
        .isInstanceOf(BusinessException.class);
    SystemConfigService.validate(SystemConfigDefinition.UPLOAD_URL, "");
    assertThatThrownBy(
            () -> SystemConfigService.validate(SystemConfigDefinition.SHOP_MAX_PER_COMPANY, "0"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> SystemConfigService.validate(SystemConfigDefinition.SHOP_MAX_PER_COMPANY, "1001"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> SystemConfigService.validate(SystemConfigDefinition.KOL_INVITE_TTL_HOURS, "73"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldValidateRequiredSiteNameAndMailFromFormat() {
    SystemConfigService.validate(SystemConfigDefinition.SITE_NAME, "CRM");
    SystemConfigService.validate(SystemConfigDefinition.MAIL_FROM, "CRM <mail@example.com>");
    assertThatThrownBy(() -> SystemConfigService.validate(SystemConfigDefinition.SITE_NAME, ""))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> SystemConfigService.validate(SystemConfigDefinition.MAIL_FROM, "not an email"))
        .isInstanceOf(BusinessException.class);
  }
}
