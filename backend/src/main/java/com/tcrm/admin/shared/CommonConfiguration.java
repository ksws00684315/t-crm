package com.tcrm.admin.shared;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import jakarta.validation.MessageInterpolator;
import java.math.BigDecimal;
import java.util.Locale;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.LocaleResolver;
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
   * Resolves the request language (ADR-0.15): only {@code zh-CN} and {@code en-US} exist, a missing
   * or unsupported {@code Accept-Language} yields English regardless of the server's default
   * locale.
   */
  @Bean
  public LocaleResolver localeResolver() {
    return new SupportedLocaleResolver();
  }

  /**
   * Validation messages follow the request language (ADR-0.15). Hibernate Validator passes the JVM
   * default locale into interpolation, so the wrapper ignores the incoming locale and resolves
   * through the request's locale context instead; anything that is not one of the two supported
   * languages — and every locale outside a request — becomes English.
   */
  @Bean
  public ValidationConfigurationCustomizer localeAwareValidationMessages() {
    return configuration ->
        configuration.messageInterpolator(
            new RequestLocaleMessageInterpolator(configuration.getDefaultMessageInterpolator()));
  }

  /**
   * The company line runs before pagination so the page is counted inside the company. System scope
   * and the two ignored tables are decided by {@link CompanyTenantLineHandler}.
   */
  @Bean
  public MybatisPlusInterceptor pagination(CompanyTenantLineHandler tenantLineHandler) {
    var interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
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

  /** Interpolates in the request language only; everything else becomes English. */
  private record RequestLocaleMessageInterpolator(MessageInterpolator delegate)
      implements MessageInterpolator {
    @Override
    public String interpolate(String template, Context context) {
      return delegate.interpolate(template, context, AppLocale.current().locale());
    }

    @Override
    public String interpolate(String template, Context context, Locale ignored) {
      return delegate.interpolate(template, context, AppLocale.current().locale());
    }
  }
}
