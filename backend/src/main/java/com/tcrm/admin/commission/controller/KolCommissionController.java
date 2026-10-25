package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.KolCommissionService;
import com.tcrm.admin.commission.service.KolCommissionService.FundRecord;
import com.tcrm.admin.commission.service.KolCommissionService.Income;
import com.tcrm.admin.commission.service.KolCommissionService.Overview;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator commission endpoints (task 62, requirement 11.02, design §6.3). The KOL identity comes
 * from the login token only; the tenant scope of the multi-company plugin isolates companies.
 */
@RestController
public class KolCommissionController {
  private final KolCommissionService commissions;
  private final CurrentKolContext current;

  public KolCommissionController(KolCommissionService commissions, CurrentKolContext current) {
    this.commissions = commissions;
    this.current = current;
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/commissions/overview")
  public R<OverviewVO> overview() {
    KolAccountDTO kol = current.kol();
    return R.ok(OverviewVO.from(commissions.overview(kol.tenantId(), kol.id())));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/fund-records")
  public R<PageResult<FundRecordVO>> fundRecords(@Valid @ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    PageResult<FundRecord> page =
        commissions.fundRecords(kol.tenantId(), kol.id(), query.pageNumber(), query.pageSize());
    return R.ok(page.map(FundRecordVO::from));
  }

  /** Standard page params. */
  public record PageQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  /** Overview payload (ADR-032 appendix B). */
  public record OverviewVO(Income income, String asOf) {
    static OverviewVO from(Overview overview) {
      return new OverviewVO(overview.income(), overview.asOf().toString());
    }
  }

  /** One unified payment record. */
  public record FundRecordVO(
      String id,
      String type,
      String appliedAt,
      String applicationNo,
      String amount,
      String currency,
      String maskedRecipient,
      String receivedAt,
      String publicStatus,
      String sourceId) {
    static FundRecordVO from(FundRecord row) {
      return new FundRecordVO(
          String.valueOf(row.id()),
          row.type(),
          row.appliedAt() == null ? null : row.appliedAt().toString(),
          row.applicationNo(),
          row.amount(),
          row.currency(),
          row.maskedRecipient(),
          row.receivedAt() == null ? null : row.receivedAt().toString(),
          row.publicStatus(),
          String.valueOf(row.sourceId()));
    }
  }
}
