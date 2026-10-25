package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.CommissionRuleVersionMapper;
import com.tcrm.admin.commission.model.CommissionRuleVersionDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company settlement config (task 62, requirement 11.03, ADR-032 §4): append-only versions of the
 * settlement currency, minimum withdrawal amount and per-channel freeze rules. Version 0 means "no
 * version saved yet" and the defaults apply (CNY, 200.00 minimum, Weidian SETTLE_TIME 7 days).
 */
@Service
public class CommissionRuleConfigService {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(CommissionRuleConfigService.class);
  static final BigDecimal DEFAULT_MINIMUM = new BigDecimal("200.00");
  static final int DEFAULT_DAYS = 7;
  static final int MIN_VERSION_WHEN_EMPTY = 0;

  private final CommissionRuleVersionMapper versions;
  private final ExternalAuditApi audit;
  private final Clock clock;

  @Autowired
  public CommissionRuleConfigService(CommissionRuleVersionMapper versions, ExternalAuditApi audit) {
    this(versions, audit, Clock.systemUTC());
  }

  CommissionRuleConfigService(
      CommissionRuleVersionMapper versions, ExternalAuditApi audit, Clock clock) {
    this.versions = versions;
    this.audit = audit;
    this.clock = clock;
  }

  /** Current config: the latest version, or the defaults with {@code version = 0}. */
  @Transactional(readOnly = true)
  public CurrentConfig current() {
    CommissionRuleVersionDO row = versions.findLatest();
    if (row == null) {
      return defaults();
    }
    ChannelRule channel = parseChannel(row.getChannelRules());
    return new CurrentConfig(
        row.getVersionNo(),
        row.getMinimumWithdrawalCny(),
        channel.basis(),
        channel.days(),
        row.getCreateTime());
  }

  /**
   * Saves the next version; {@code expectedVersion} is the optimistic lock (409 on mismatch, also
   * when the config changed between read and write).
   */
  @Transactional(rollbackFor = Exception.class)
  public CurrentConfig save(AccountDTO user, int expectedVersion, BigDecimal minimum, int days) {
    if (user == null || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (minimum == null
        || minimum.scale() > 2
        || minimum.signum() < 0
        || minimum.compareTo(new BigDecimal("999999999999.99")) > 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (days < 0 || days > 365) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          CommissionRuleVersionDO latest = versions.findLatest();
          int currentVersion = latest == null ? MIN_VERSION_WHEN_EMPTY : latest.getVersionNo();
          if (expectedVersion != currentVersion) {
            throw new BusinessException(ErrorCode.COMMISSION_CONFIG_VERSION_CONFLICT);
          }
          Instant now = clock.instant();
          CommissionRuleVersionDO row = new CommissionRuleVersionDO();
          row.setId(IdWorker.getId());
          row.setVersionNo(currentVersion + 1);
          row.setCurrency("CNY");
          row.setMinimumWithdrawalCny(minimum.setScale(2, java.math.RoundingMode.UNNECESSARY));
          row.setChannelRules("{\"WEIDIAN\":{\"basis\":\"SETTLE_TIME\",\"days\":" + days + "}}");
          row.setActorId(user.id());
          row.setCreateTime(now);
          versions.insert(row);
          audit.recordTenantWriteOperation(
              user.tenantId(),
              user.id(),
              "CommissionRuleConfig",
              "Save settlement config",
              "PUT",
              "/admin-api/commission/settlement-configs/current",
              "{\"version\":"
                  + row.getVersionNo()
                  + ",\"minimum\":\""
                  + row.getMinimumWithdrawalCny()
                  + "\",\"days\":"
                  + days
                  + "}");
          return new CurrentConfig(
              row.getVersionNo(), row.getMinimumWithdrawalCny(), "SETTLE_TIME", days, now);
        });
  }

  private static CurrentConfig defaults() {
    return new CurrentConfig(
        MIN_VERSION_WHEN_EMPTY, DEFAULT_MINIMUM, "SETTLE_TIME", DEFAULT_DAYS, null);
  }

  static ChannelRule parseChannel(String channelRules) {
    int days = DEFAULT_DAYS;
    String basis = "SETTLE_TIME";
    if (channelRules == null || channelRules.isBlank()) {
      return new ChannelRule(basis, days);
    }
    try {
      tools.jackson.databind.JsonNode channel =
          tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readTree(channelRules)
              .get("WEIDIAN");
      if (channel != null) {
        if (channel.get("days") != null && channel.get("days").isNumber()) {
          days = channel.get("days").asInt();
        }
        if (channel.get("basis") != null
            && "COMPLETED_PLUS_DAYS".equals(channel.get("basis").asString())) {
          basis = "COMPLETED_PLUS_DAYS";
        }
      }
    } catch (RuntimeException e) {
      // Keep defaults for unreadable rules; the JSONB CHECK keeps rows objects at least.
      LOG.warn("unreadable channel rules {}: {}", channelRules, e.getMessage());
    }
    return new ChannelRule(basis, days);
  }

  record ChannelRule(String basis, int days) {}

  /** Saved config projection; {@code savedAt} is null for the default (unsaved) config. */
  public record CurrentConfig(
      int version, BigDecimal minimumCny, String weidianBasis, int weidianDays, Instant savedAt) {

    public List<ChannelVO> channels() {
      return List.of(new ChannelVO("WEIDIAN", "WEIDIAN", weidianBasis, weidianDays));
    }
  }

  /** Channel rule projection for the controller VO. */
  public record ChannelVO(String code, String name, String basis, int days) {}
}
