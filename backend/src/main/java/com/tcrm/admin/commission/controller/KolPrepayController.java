package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** KOL-facing prepay views are a projection of paid records only. */
@RestController
public class KolPrepayController {
  private final PrepayService prepays;
  private final CurrentKolContext current;

  public KolPrepayController(PrepayService prepays, CurrentKolContext current) {
    this.prepays = prepays;
    this.current = current;
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays")
  public R<PageResult<KolPaidPrepayVO>> list(@Valid @ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    PageResult<PrepayService.PaidPrepay> page =
        prepays.listPaid(kol.tenantId(), kol.id(), query.pageNumber(), query.pageSize());
    return R.ok(page == null ? null : page.map(KolPaidPrepayVO::from));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/{id}")
  public R<KolPaidPrepayVO> detail(@PathVariable @Positive Long id) {
    KolAccountDTO kol = current.kol();
    return R.ok(KolPaidPrepayVO.from(prepays.paid(kol.tenantId(), kol.id(), id)));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/ledger")
  public R<PageResult<KolPrepayLedgerRowVO>> ledger(@Valid @ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    PageResult<PrepayService.LedgerRow> page =
        prepays.ledger(kol.tenantId(), kol.id(), query.pageNumber(), query.pageSize());
    return R.ok(page == null ? null : page.map(KolPrepayLedgerRowVO::from));
  }

  public record PageQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }
}
