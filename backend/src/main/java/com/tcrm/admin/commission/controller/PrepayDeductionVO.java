package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;

/** Commission deduction progress for a paid prepay. */
public record PrepayDeductionVO(
    String postedAmountCny, String recoveredAmountCny, String remainingAmountCny) {
  static PrepayDeductionVO from(PrepayService.Deduction deduction) {
    if (deduction == null) {
      return null;
    }
    return new PrepayDeductionVO(
        deduction.postedAmountCny(),
        deduction.recoveredAmountCny(),
        deduction.remainingAmountCny());
  }
}
