package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.DetailFilter;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.Line;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.SettlementRow;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement detail list (task 62, requirement 13.05, design §6.2): {@code commission:view}; BD OWN
 * scope hides mixed-attribution orders.
 */
@RestController
public class SettlementDetailController {
  private final CommissionSettlementDetailService details;
  private final CurrentUserContext current;

  public SettlementDetailController(
      CommissionSettlementDetailService details, CurrentUserContext current) {
    this.details = details;
    this.current = current;
  }

  @SaCheckPermission("commission:view")
  @GetMapping("/admin-api/commission/settlement-details")
  public R<PageResult<SettlementVO>> list(@Valid @ModelAttribute Query query) {
    AccountDTO user = current.user();
    PageResult<SettlementRow> page =
        details.page(
            user,
            new DetailFilter(
                query.orderNo(),
                query.channel(),
                query.shopId(),
                query.pageNumber(),
                query.pageSize()));
    return R.ok(page.map(SettlementVO::from));
  }

  /** Query of the settlement detail list; {@code channel} currently accepts WEIDIAN only. */
  public record Query(
      @Size(max = 32) String orderNo,
      @Pattern(regexp = "WEIDIAN|") String channel,
      @Min(1) Long shopId,
      @Min(1) Integer page,
      @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  /** One per-order clearing row (ADR-032 appendix A {@code Settlement}). */
  public record SettlementVO(
      String id,
      String orderNo,
      String channel,
      String shop,
      List<String> kolIds,
      List<String> kolNames,
      String payment,
      String refund,
      String net,
      String commission,
      List<String> rates,
      String retained,
      String status,
      String businessAt,
      List<LineVO> lines) {
    static SettlementVO from(SettlementRow row) {
      return new SettlementVO(
          String.valueOf(row.id()),
          row.orderNo(),
          row.channel(),
          row.shop(),
          row.kolIds().stream().map(String::valueOf).toList(),
          row.kolNames(),
          row.payment(),
          row.refund(),
          row.net(),
          row.commission(),
          row.rates(),
          row.retained(),
          row.status(),
          row.businessAt() == null ? null : row.businessAt().toString(),
          row.lines().stream().map(LineVO::from).toList());
    }
  }

  /** Per-item commission line inside a settlement row. */
  public record LineVO(
      String id,
      String orderNo,
      String itemId,
      String image,
      String base,
      String rate,
      Integer rateVersion,
      String ruleVersion,
      String commission,
      String adjustment,
      String releaseBasis,
      String businessAt,
      String direction,
      String subject,
      String kolId,
      String kol) {
    static LineVO from(Line line) {
      return new LineVO(
          String.valueOf(line.id()),
          line.orderNo(),
          String.valueOf(line.itemId()),
          line.image(),
          line.base(),
          line.rate(),
          line.rateVersion(),
          line.ruleVersion() == null ? null : String.valueOf(line.ruleVersion()),
          line.commission(),
          line.adjustment(),
          line.releaseBasis(),
          line.businessAt() == null ? null : line.businessAt().toString(),
          line.direction(),
          line.subject(),
          line.kolId() == null ? null : String.valueOf(line.kolId()),
          line.kol());
    }
  }
}
