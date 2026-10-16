package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kol.service.BdTransferExistsException;
import com.tcrm.admin.kol.service.BdTransferService;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super-administrator BD transfers (design §6.2). Missing/cross-company IDs return 404; invalid
 * targets return KOL_INVALID_BD_USER (422); existing reservations return KOL_TRANSFER_EXISTS (409)
 * with data containing the conflicting KOL codes.
 */
@RestController
@RequestMapping("/admin-api/kol/bd-transfers")
@SaCheckRole("SUPER_ADMIN")
public class BdTransferController {
  private final BdTransferService transfers;

  public BdTransferController(BdTransferService transfers) {
    this.transfers = transfers;
  }

  /** Example: {"kolIds":["123"],"toBdUserId":"456"}; null effectiveAt executes immediately. */
  @PostMapping
  public R<BdTransferResultVO> create(@Valid @RequestBody BdTransferRequest request) {
    return R.ok(BdTransferResultVO.from(transfers.create(request.toCommand())));
  }

  /** Sorted by effectiveAt descending, then id descending; page defaults to 1, size to 20. */
  @GetMapping
  public R<PageResult<BdTransferVO>> list(@Valid @ModelAttribute BdTransferQuery query) {
    return R.ok(transfers.page(query.toFilter()).map(BdTransferVO::from));
  }

  /** Cancels a reservation; DONE/CANCELLED return KOL_TRANSFER_NOT_SCHEDULED (409). */
  @DeleteMapping("/{id}")
  public R<Void> cancel(@PathVariable @Min(1) Long id) {
    transfers.cancel(id);
    return R.ok(null);
  }

  @ExceptionHandler(BdTransferExistsException.class)
  public ResponseEntity<R<List<String>>> conflicts(BdTransferExistsException e) {
    return ResponseEntity.status(409).body(R.error(ErrorCode.KOL_TRANSFER_EXISTS, e.getKolCodes()));
  }
}
