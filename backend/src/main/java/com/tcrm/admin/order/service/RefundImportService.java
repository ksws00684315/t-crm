package com.tcrm.admin.order.service;

import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import org.springframework.stereotype.Service;

/** Refund Excel import through the batch task framework (task 18 §4.3). */
@Service
public class RefundImportService {
  private final ImportTaskApi imports;
  private final ShopAccessApi shops;
  private final OrderWriter writer;

  public RefundImportService(ImportTaskApi imports, ShopAccessApi shops, OrderWriter writer) {
    this.imports = imports;
    this.shops = shops;
    this.writer = writer;
  }

  public byte[] template() {
    return imports.template(RefundImportColumn.headerKeys());
  }

  public Long submit(ImportFileDTO file) {
    return imports.submitImport(
        TaskType.REFUND_IMPORT,
        RefundImportColumn.headerKeys(),
        file,
        () -> new RefundImportProcessor(shops, writer),
        TaskType.REFUND_IMPORT.maxRows());
  }
}
