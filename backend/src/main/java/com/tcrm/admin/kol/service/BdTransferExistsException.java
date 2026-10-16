package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;

/** Entire batch is rejected; codes identify every KOL that already has a reservation. */
public class BdTransferExistsException extends BusinessException {
  private final List<String> kolCodes;

  public BdTransferExistsException(List<String> kolCodes) {
    super(ErrorCode.KOL_TRANSFER_EXISTS);
    this.kolCodes = List.copyOf(kolCodes);
  }

  public List<String> getKolCodes() {
    return kolCodes;
  }
}
