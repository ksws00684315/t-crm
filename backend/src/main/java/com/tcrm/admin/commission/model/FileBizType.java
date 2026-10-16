package com.tcrm.admin.commission.model;

/** Business record a crm_file row belongs to (task 15a §4.1.1). Only PAYOUT_QR is used so far. */
public enum FileBizType {
  PAYOUT_QR,
  WITHDRAWAL_VOUCHER,
  PREPAY_ATTACHMENT,
  PREPAY_VOUCHER
}
