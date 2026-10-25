package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.commission.service.CommissionRuleConfigService;
import com.tcrm.admin.commission.service.CommissionRuleConfigService.CurrentConfig;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement config endpoints (task 62, requirement 11.03, design §6.2): read needs {@code
 * commission:view}; only company super admins save, with the read version as optimistic lock.
 */
@RestController
public class SettlementConfigController {
  private final CommissionRuleConfigService configs;
  private final CurrentUserContext current;

  public SettlementConfigController(
      CommissionRuleConfigService configs, CurrentUserContext current) {
    this.configs = configs;
    this.current = current;
  }

  @SaCheckPermission("commission:view")
  @GetMapping("/admin-api/commission/settlement-configs/current")
  public R<SettlementConfigVO> current() {
    return R.ok(SettlementConfigVO.from(configs.current()));
  }

  @SaCheckRole("SUPER_ADMIN")
  @PutMapping("/admin-api/commission/settlement-configs/current")
  public R<SettlementConfigVO> save(@Valid @RequestBody SaveRequest request) {
    CurrentConfig saved =
        configs.save(current.user(), request.version(), request.minimum(), request.days());
    return R.ok(SettlementConfigVO.from(saved));
  }

  /** Save request: {@code version} comes back unchanged from the read endpoint. */
  public record SaveRequest(
      @NotNull @Min(0) Integer version,
      @NotNull @DecimalMin("0") BigDecimal minimum,
      @NotNull @Min(0) @Max(365) Integer days) {}

  /** {@code channels} currently holds the single Weidian rule (ADR-032 appendix A). */
  public record SettlementConfigVO(
      String currency, String minimum, int version, List<ChannelVO> channels) {

    static SettlementConfigVO from(CurrentConfig config) {
      return new SettlementConfigVO(
          "CNY",
          config.minimumCny().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
          config.version(),
          config.channels().stream()
              .map(c -> new ChannelVO(c.code(), c.name(), c.basis(), c.days()))
              .toList());
    }
  }

  /** One channel freeze rule. */
  public record ChannelVO(String code, String name, String basis, int days) {}
}
