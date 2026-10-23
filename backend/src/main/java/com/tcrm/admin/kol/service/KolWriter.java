package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.CurrentTenantResolver;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.CommissionSettlementApi;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inserts a KOL and, when a BD is assigned, its first ownership period, in one transaction. Shared
 * by the create API and the Excel import.
 */
@Component
public class KolWriter {
  static final int MAX_ATTEMPTS = 3;
  private static final String EMAIL_KEY = "uk_crm_kol_email";
  private static final String CODE_KEY = "uk_crm_kol_kol_code";

  private final KolMapper kols;
  private final KolBdHistoryMapper history;
  private final KolCodeGenerator codes;
  private final TransactionTemplate tx;
  private final CommissionSettlementApi commission;
  private final CurrentTenantResolver tenants;

  public KolWriter(
      KolMapper kols,
      KolBdHistoryMapper history,
      KolCodeGenerator codes,
      TransactionTemplate tx,
      CommissionSettlementApi commission,
      CurrentTenantResolver tenants) {
    this.kols = kols;
    this.history = history;
    this.codes = codes;
    this.tx = tx;
    this.commission = commission;
    this.tenants = tenants;
  }

  /**
   * Inserts the KOL with {@code requestedCode}, or with a generated code when it is null. A
   * generated code that loses a race is replaced (each attempt is its own transaction).
   *
   * @throws BusinessException KOL_EMAIL_EXISTS or KOL_CODE_EXISTS (409)
   */
  public KolDO insert(KolDO kol, String requestedCode, Long actor) {
    kol.setCreateBy(actor);
    kol.setUpdateBy(actor);
    kol.setDeleted(false);
    for (int attempt = 1; ; attempt++) {
      kol.setId(null);
      kol.setKolCode(requestedCode != null ? requestedCode : codes.next());
      try {
        tx.executeWithoutResult(status -> insertRows(kol, actor));
        return kol;
      } catch (DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains(EMAIL_KEY)) {
          throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
        }
        if (requestedCode != null || !message.contains(CODE_KEY) || attempt >= MAX_ATTEMPTS) {
          throw new BusinessException(
              message.contains(CODE_KEY) ? ErrorCode.KOL_CODE_EXISTS : ErrorCode.COMMON_CONFLICT);
        }
      }
    }
  }

  private void insertRows(KolDO kol, Long actor) {
    kols.insert(kol);
    if (kol.getDefaultRate() != null && actor != null) {
      Long tenantId = kol.getTenantId() == null ? TenantScope.companyId() : kol.getTenantId();
      if (tenantId == null) {
        tenantId = tenants.currentLoginTenantId();
      }
      if (tenantId == null) {
        throw new IllegalStateException("KOL commission rate needs a company scope");
      }
      commission.recordRateVersion(
          tenantId,
          kol.getId(),
          kol.getDefaultRate(),
          Instant.now(),
          actor,
          "Initial KOL default rate; effective from KOL creation");
    }
    if (kol.getBdUserId() != null) {
      KolBdHistoryDO period = new KolBdHistoryDO();
      period.setKolId(kol.getId());
      period.setBdUserId(kol.getBdUserId());
      period.setStartTime(Instant.now());
      period.setCreateBy(actor);
      period.setUpdateBy(actor);
      period.setDeleted(false);
      history.insert(period);
    }
  }
}
