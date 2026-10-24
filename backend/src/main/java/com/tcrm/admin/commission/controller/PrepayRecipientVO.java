package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;

/** Full recipient snapshot returned by the dedicated admin read API. */
public record PrepayRecipientVO(
    String type, String currency, String network, String holderName, String account) {
  static PrepayRecipientVO from(PrepayService.Recipient recipient) {
    if (recipient == null) {
      return null;
    }
    return new PrepayRecipientVO(
        recipient.type(),
        recipient.currency(),
        recipient.network(),
        recipient.holderName(),
        recipient.account());
  }
}
