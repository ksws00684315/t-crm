package com.tcrm.admin.commission.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tcrm.admin.commission.service.PrepayService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Admin detail for a prepay application, including timeline and payment facts. */
public record PrepayDetailVO(
    Long id,
    String prepayNo,
    String financeTaskType,
    Long financeTaskId,
    Long kolId,
    String kolCode,
    String kolName,
    Long applicantId,
    String applicantName,
    String amountCny,
    String currency,
    String status,
    Instant createdAt,
    String reason,
    LocalDate expectedPayDate,
    Boolean deductFromCommission,
    Long payoutAccountId,
    PrepayPayoutOptionVO payoutAccountSnapshot,
    List<PrepayFileVO> attachments,
    ApprovalVO approval,
    PaymentVO payment,
    List<TimelineVO> timeline,
    PrepayDeductionVO deduction,
    String walletBalanceCny) {
  @JsonProperty("applicantRole")
  public String applicantRole() {
    return "BUSINESS";
  }

  public record ApprovalVO(
      String decision,
      Long operatorId,
      Instant time,
      String reason,
      String operatorName,
      String operatorRole) {
    static ApprovalVO from(PrepayService.Approval approval) {
      if (approval == null) {
        return null;
      }
      return new ApprovalVO(
          approval.decision(),
          approval.operatorId(),
          approval.time(),
          approval.reason(),
          approval.operatorName(),
          approval.operatorRole());
    }
  }

  public record PaymentVO(
      String payAmountCny,
      String payCurrency,
      Instant payTime,
      String payMethod,
      String payRemark,
      Long voucherFileId,
      Long operatorId,
      String operatorName,
      String operatorRole) {
    static PaymentVO from(PrepayService.Payment payment) {
      if (payment == null) {
        return null;
      }
      return new PaymentVO(
          payment.payAmountCny(),
          payment.payCurrency(),
          payment.payTime(),
          payment.payMethod(),
          payment.payRemark(),
          payment.voucherFileId(),
          payment.operatorId(),
          payment.operatorName(),
          payment.operatorRole());
    }
  }

  public record TimelineVO(
      String action,
      Long operatorId,
      Instant time,
      String reason,
      String operatorName,
      String operatorRole) {
    static TimelineVO from(PrepayService.Timeline item) {
      return new TimelineVO(
          item.action(),
          item.operatorId(),
          item.time(),
          item.reason(),
          item.operatorName(),
          item.operatorRole());
    }
  }

  static PrepayDetailVO from(PrepayService.PrepayDetail detail) {
    if (detail == null) {
      return null;
    }
    return new PrepayDetailVO(
        detail.id(),
        detail.prepayNo(),
        detail.financeTaskType(),
        detail.financeTaskId(),
        detail.kolId(),
        detail.kolCode(),
        detail.kolName(),
        detail.applicantId(),
        detail.applicantName(),
        detail.amountCny(),
        detail.currency(),
        detail.status(),
        detail.createdAt(),
        detail.reason(),
        detail.expectedPayDate(),
        detail.deductFromCommission(),
        detail.payoutAccountId(),
        PrepayPayoutOptionVO.from(detail.payoutAccountSnapshot()),
        detail.attachments() == null
            ? List.of()
            : detail.attachments().stream().map(PrepayFileVO::from).toList(),
        ApprovalVO.from(detail.approval()),
        PaymentVO.from(detail.payment()),
        detail.timeline() == null
            ? List.of()
            : detail.timeline().stream().map(TimelineVO::from).toList(),
        PrepayDeductionVO.from(detail.deduction()),
        detail.walletBalanceCny());
  }
}
