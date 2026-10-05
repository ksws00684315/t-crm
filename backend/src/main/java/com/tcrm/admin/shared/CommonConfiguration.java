package com.tcrm.admin.shared;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.validation.MessageInterpolator;
import java.math.BigDecimal;
import java.util.Locale;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Configuration
public class CommonConfiguration {
  @Bean
  public JsonMapperBuilderCustomizer jsonCustomizer() {
    return builder ->
        builder.addModule(
            new SimpleModule()
                .addSerializer(Long.class, ToStringSerializer.instance)
                .addSerializer(Long.TYPE, ToStringSerializer.instance)
                .addSerializer(BigDecimal.class, ToStringSerializer.instance));
  }

  /**
   * Validation messages are part of the API contract (English, like every backend message), so they
   * must not depend on the server's default locale or on the request's Accept-Language header.
   */
  @Bean
  public ValidationConfigurationCustomizer englishValidationMessages() {
    return configuration ->
        configuration.messageInterpolator(
            new LocaleSpecificMessageInterpolator(
                configuration.getDefaultMessageInterpolator(), Locale.ENGLISH));
  }

  @Bean
  public MybatisPlusInterceptor pagination() {
    var interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
    return interceptor;
  }

  /** An empty TCRM_REDIS_PASSWORD must mean "no AUTH", not AUTH with an empty password. */
  @Bean
  public RedissonAutoConfigurationCustomizer redissonBlankPasswordCustomizer() {
    return config -> {
      if (config.getPassword() != null && config.getPassword().isBlank()) {
        config.setPassword(null);
      }
      if (config.isSingleConfig()) {
        var single = config.useSingleServer();
        if (single.getPassword() != null && single.getPassword().isBlank()) {
          single.setPassword(null);
        }
      }
    };
  }

  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /** Always interpolates with one fixed locale, ignoring the locale the caller passes in. */
  private record LocaleSpecificMessageInterpolator(MessageInterpolator delegate, Locale locale)
      implements MessageInterpolator {
    @Override
    public String interpolate(String template, Context context) {
      return delegate.interpolate(template, context, locale);
    }

    @Override
    public String interpolate(String template, Context context, Locale ignored) {
      return delegate.interpolate(template, context, locale);
    }
  }
}
