package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
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
  private final LinkTemplateService templates;

  public KolImportService(
      ImportTaskApi imports,
      CurrentUserContext current,
      KolMapper kols,
      KolWriter writer,
      BdUserApi bdUsers,
      LinkTemplateService templates) {
    this.imports = imports;
    this.current = current;
    this.kols = kols;
    this.writer = writer;
    this.bdUsers = bdUsers;
    this.templates = templates;
  }

  public byte[] template() {
    return imports.template(KolImportColumn.headerKeys());
  }

  /**
   * Validates and queues the file; returns the task ID. Files built with the pre-task-11 template
   * (without the four profile columns) are still accepted (task 11 §4.3).
   */
  public Long submit(ImportFileDTO file) {
    AccountDTO user = current.user();
    ImportActor actor = new ImportActor(user.id(), user.ownScope());
    return imports.submitImport(
        TaskType.KOL_IMPORT,
        KolImportColumn.headerKeys(),
        KolImportColumn.legacyHeaderKeys(),
        file,
        () -> new KolImportProcessor(actor, kols, writer, bdUsers, templates),
        TaskType.KOL_IMPORT.maxRows());
  }
}
