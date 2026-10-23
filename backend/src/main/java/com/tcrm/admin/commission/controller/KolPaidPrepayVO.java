package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;
import java.time.Instant;

/** Paid prepay projection for the signed-in creator. */
public record KolPaidPrepayVO(
    Long id,
    String prepayNo,
    String status,
    String payAmountCny,
    String payCurrency,
    Instant payTime,
    String payMethod,
    Long voucherFileId,
    Boolean deductFromCommission,
    PrepayDeductionVO deduction,
    String walletBalanceCny) {
  static KolPaidPrepayVO from(PrepayService.PaidPrepay row) {
    if (row == null) {
      return null;
    }
    return new KolPaidPrepayVO(
        row.id(),
        row.prepayNo(),
        row.status(),
        row.payAmountCny(),
        row.payCurrency(),
        row.payTime(),
        row.payMethod(),
        row.voucherFileId(),
        row.deductFromCommission(),
        PrepayDeductionVO.from(row.deduction()),
        row.walletBalanceCny());
  }
}
