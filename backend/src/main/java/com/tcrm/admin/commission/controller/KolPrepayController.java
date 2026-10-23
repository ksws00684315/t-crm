package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.commission.service.PrepayService.LedgerRow;
import com.tcrm.admin.commission.service.PrepayService.PaidPrepay;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
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
  public R<PageResult<PaidPrepay>> list(@ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    int page = query.pageNumber();
    int size = query.pageSize();
    validatePage(page, size);
    return R.ok(prepays.listPaid(kol.tenantId(), kol.id(), page, size));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/{id}")
  public R<PaidPrepay> detail(@PathVariable @Positive Long id) {
    KolAccountDTO kol = current.kol();
    return R.ok(prepays.paid(kol.tenantId(), kol.id(), id));
  }

  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/prepays/ledger")
  public R<PageResult<LedgerRow>> ledger(@ModelAttribute PageQuery query) {
    KolAccountDTO kol = current.kol();
    int page = query.pageNumber();
    int size = query.pageSize();
    validatePage(page, size);
    return R.ok(prepays.ledger(kol.tenantId(), kol.id(), page, size));
  }

  private static void validatePage(int page, int size) {
    if (page < 1 || size < 1 || size > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
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
