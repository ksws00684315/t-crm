package com.tcrm.admin.commission.model;

/** Frozen recipient snapshot, including the ciphertext. */
public record PrepayRecipientRow(
    String payoutAccountType,
    String payoutCurrency,
    String payoutNetwork,
    String payoutHolderName,
    String payoutAccountCipher,
    String prepayNo) {}
