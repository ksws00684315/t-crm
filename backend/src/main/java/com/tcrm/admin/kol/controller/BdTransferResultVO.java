package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.BdTransferResultDTO;

public record BdTransferResultVO(int immediate, int scheduled, int skipped) {
  static BdTransferResultVO from(BdTransferResultDTO d) {
    return new BdTransferResultVO(d.immediate(), d.scheduled(), d.skipped());
  }
}
