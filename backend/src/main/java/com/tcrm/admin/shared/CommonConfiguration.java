package com.tcrm.admin.shared;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.math.BigDecimal;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
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
}
