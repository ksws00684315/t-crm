package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import org.springframework.stereotype.Service;

/** KOL Excel import through the batch task framework; template generated from the column enum. */
@Service
public class KolImportService {
  private final ImportTaskApi imports;
  private final CurrentUserContext current;
  private final KolMapper kols;
  private final KolWriter writer;
  private final BdUserApi bdUsers;

  public KolImportService(
      ImportTaskApi imports,
      CurrentUserContext current,
      KolMapper kols,
      KolWriter writer,
      BdUserApi bdUsers) {
    this.imports = imports;
    this.current = current;
    this.kols = kols;
    this.writer = writer;
    this.bdUsers = bdUsers;
  }

  public byte[] template() {
    return imports.template(KolImportColumn.headers());
  }

  /** Validates and queues the file; returns the task ID. FINANCE may not import (403). */
  public Long submit(ImportFileDTO file) {
    AccountDTO user = current.user();
    if (user.role() == Role.FINANCE) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    ImportActor actor = new ImportActor(user.id(), KolService.own(user));
    return imports.submitImport(
        TaskType.KOL_IMPORT,
        KolImportColumn.headers(),
        file,
        () -> new KolImportProcessor(actor, kols, writer, bdUsers),
        TaskType.KOL_IMPORT.maxRows());
  }
}
