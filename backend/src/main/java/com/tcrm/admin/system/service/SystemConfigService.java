package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.mapper.SysConfigMapper;
import com.tcrm.admin.system.model.SysConfigDO;
import com.tcrm.admin.system.model.SysOperLogDO;
import com.tcrm.admin.system.model.SystemConfigDefinition;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-wide configuration registry, cache, validation, and persistence (task 34). */
@Service
public class SystemConfigService implements SystemConfigApi {
  public static final String REDIRECT_FALLBACK_REDIS_KEY = "crm:config:redirect-fallback-url";
  private final SysConfigMapper configs;
  private final Environment environment;
  private final StringRedisTemplate redis;
  private final CurrentUserContext current;
  private final AuditWriter audit;
  private final Map<String, String> defaults = new ConcurrentHashMap<>();
  private final Map<String, String> values = new ConcurrentHashMap<>();

  public SystemConfigService(
      SysConfigMapper configs,
      Environment environment,
      StringRedisTemplate redis,
      CurrentUserContext current,
      AuditWriter audit) {
    this.configs = configs;
    this.environment = environment;
    this.redis = redis;
    this.current = current;
    this.audit = audit;
  }

  @jakarta.annotation.PostConstruct
  public void initialize() {
    for (SystemConfigDefinition definition : SystemConfigDefinition.values()) {
      String value =
          definition.property() == null
              ? definition.fallback()
              : environment.getProperty(definition.property(), definition.fallback());
      validate(definition, value);
      defaults.put(definition.key(), value);
      values.put(definition.key(), value);
    }
    TenantScope.runSystem(
        "load platform system configuration",
        () ->
            configs
                .selectList(new LambdaQueryWrapper<SysConfigDO>())
                .forEach(
                    row -> {
                      SystemConfigDefinition definition = requireDefinition(row.getConfigKey());
                      validate(definition, row.getConfigValue());
                      values.put(row.getConfigKey(), row.getConfigValue());
                    }));
    validateRelations(values);
    redis.opsForValue().set(REDIRECT_FALLBACK_REDIS_KEY, getValue("url.redirect-fallback"));
  }

  @Override
  public String getValue(String key) {
    String value = values.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Unknown system configuration key: " + key);
    }
    return value;
  }

  @Override
  public int getInt(String key) {
    return Integer.parseInt(getValue(key));
  }

  public String getDefaultValue(String key) {
    String value = defaults.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Unknown system configuration key: " + key);
    }
    return value;
  }

  public boolean isOverridden(String key) {
    return TenantScope.callSystem(
        "read system configuration override state",
        () ->
            configs.selectCount(
                    new LambdaQueryWrapper<SysConfigDO>().eq(SysConfigDO::getConfigKey, key))
                > 0);
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(String key, String value) {
    SystemConfigDefinition definition = requireDefinition(key);
    validate(definition, value);
    Map<String, String> candidate = new ConcurrentHashMap<>(values);
    String normalizedValue = value.strip();
    candidate.put(key, normalizedValue);
    validateRelations(candidate);
    validateMailSender(key, value);
    String oldValue = getValue(key);
    TenantScope.runSystem(
        "write platform system configuration",
        () -> {
          SysConfigDO row =
              configs.selectOne(
                  new LambdaQueryWrapper<SysConfigDO>().eq(SysConfigDO::getConfigKey, key));
          if (row == null) {
            row = new SysConfigDO();
            row.setConfigKey(key);
            row.setConfigValue(normalizedValue);
            row.setUpdateBy(current.id());
            row.setUpdateTime(Instant.now());
            configs.insert(row);
          } else {
            row.setConfigValue(normalizedValue);
            row.setUpdateBy(current.id());
            row.setUpdateTime(Instant.now());
            configs.updateById(row);
          }
        });
    values.put(key, normalizedValue);
    if ("url.redirect-fallback".equals(key)) {
      redis.opsForValue().set(REDIRECT_FALLBACK_REDIS_KEY, normalizedValue);
    }
    writeAudit(definition, oldValue, normalizedValue);
  }

  @Transactional(rollbackFor = Exception.class)
  public void reset(String key) {
    SystemConfigDefinition definition = requireDefinition(key);
    String oldValue = getValue(key);
    String value = getDefaultValue(key);
    validateMailSender(key, value);
    Map<String, String> candidate = new ConcurrentHashMap<>(values);
    candidate.put(key, value);
    validateRelations(candidate);
    TenantScope.runSystem(
        "reset platform system configuration",
        () ->
            configs.delete(
                new LambdaQueryWrapper<SysConfigDO>().eq(SysConfigDO::getConfigKey, key)));
    values.put(key, value);
    if ("url.redirect-fallback".equals(key)) {
      redis.opsForValue().set(REDIRECT_FALLBACK_REDIS_KEY, value);
    }
    writeAudit(definition, oldValue, value);
  }

  private void writeAudit(SystemConfigDefinition definition, String oldValue, String newValue) {
    SysOperLogDO event = new SysOperLogDO();
    event.setUserId(current.id());
    event.setUsername(current.user().username());
    event.setModule("Platform");
    event.setAction("更新系统配置 " + definition.key());
    event.setMethod("PUT");
    event.setRequestUri("/admin-api/platform/system-configs/" + definition.key());
    event.setRequestParams(
        "{\"summary\":\""
            + jsonEscape("系统配置 " + definition.key() + "：" + oldValue + " → " + newValue)
            + "\"}");
    event.setResult("SUCCESS");
    event.setCostMs(0L);
    event.setOperTime(Instant.now());
    event.setTenantId(null);
    audit.write(event);
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }

  private void validateMailSender(String key, String value) {
    if ("mail.from".equals(key)
        && value.isBlank()
        && !environment.getProperty("crm.mail.api-key", "").isBlank()) {
      throw new BusinessException(ErrorCode.SYSTEM_CONFIG_INVALID);
    }
  }

  private static SystemConfigDefinition requireDefinition(String key) {
    SystemConfigDefinition definition = SystemConfigDefinition.find(key);
    if (definition == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return definition;
  }

  public static void validate(SystemConfigDefinition definition, String value) {
    if (value == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String trimmed = value.strip();
    try {
      switch (definition.type()) {
        case "string" -> {
          if ("mail.from".equals(definition.key()) && trimmed.isEmpty()) {
            return;
          }
          int length = trimmed.codePointCount(0, trimmed.length());
          if (trimmed.isEmpty()
              || (definition.min() != null && length < definition.min())
              || (definition.max() != null && length > definition.max())) {
            throw new IllegalArgumentException();
          }
          if ("mail.from".equals(definition.key())
              && !trimmed.isEmpty()
              && !validMailFrom(trimmed)) {
            throw new IllegalArgumentException();
          }
        }
        case "url" -> {
          if (trimmed.isEmpty() && "url.upload".equals(definition.key())) {
            return;
          }
          if (!validBaseUrl(trimmed)) {
            throw new IllegalArgumentException();
          }
        }
        default -> {
          int number = Integer.parseInt(trimmed);
          if (number < definition.min() || number > definition.max()) {
            throw new IllegalArgumentException();
          }
          if ("copy-token.default-quota".equals(definition.key()) && number > 100000) {
            throw new IllegalArgumentException();
          }
        }
      }
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.SYSTEM_CONFIG_INVALID);
    }
  }

  private static void validateRelations(Map<String, String> candidate) {
    if (Integer.parseInt(candidate.get("copy-token.default-quota"))
        > Integer.parseInt(candidate.get("copy-token.max-quota"))) {
      throw new BusinessException(ErrorCode.SYSTEM_CONFIG_INVALID);
    }
    if (Integer.parseInt(candidate.get("copy-token.default-valid-minutes"))
        > Integer.parseInt(candidate.get("copy-token.max-valid-minutes"))) {
      throw new BusinessException(ErrorCode.SYSTEM_CONFIG_INVALID);
    }
  }

  private static boolean validMailFrom(String value) {
    return value.matches("(?:[^<>\\r\\n]{1,160}\\s*)?<[^<>\\s@]+@[^<>\\s@]+\\.[^<>\\s@]+>")
        || value.matches("[^<>\\s@]+@[^<>\\s@]+\\.[^<>\\s@]+");
  }

  public static boolean validBaseUrl(String value) {
    if (value == null || value.isBlank() || value.endsWith("/")) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
        return false;
      }
      if ("https".equals(uri.getScheme())) {
        return true;
      }
      return "http".equals(uri.getScheme())
          && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
