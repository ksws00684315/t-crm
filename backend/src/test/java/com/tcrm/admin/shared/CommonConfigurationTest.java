package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;

class CommonConfigurationTest {
  @Test
  void shouldAvoidAuthWithBlankPassword_whenRedisHasNoPassword() {
    var config = new Config();
    config.setPassword(" ");
    config.useSingleServer().setAddress("redis://localhost:6379").setPassword("");
    new CommonConfiguration().redissonBlankPasswordCustomizer().customize(config);
    assertThat(config.getPassword()).isNull();
    assertThat(config.useSingleServer().getPassword()).isNull();
  }

  @Test
  void shouldKeepConfiguredPassword_whenRedisRequiresAuthentication() {
    var config = new Config();
    config.setPassword("unit-test-credential");
    config
        .useSingleServer()
        .setAddress("redis://localhost:6379")
        .setPassword("unit-test-credential");
    new CommonConfiguration().redissonBlankPasswordCustomizer().customize(config);
    assertThat(config.getPassword()).isEqualTo("unit-test-credential");
    assertThat(config.useSingleServer().getPassword()).isEqualTo("unit-test-credential");
  }
}
