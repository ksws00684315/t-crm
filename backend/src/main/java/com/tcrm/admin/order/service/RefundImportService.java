package com.tcrm.admin.order.service;

import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** Refund Excel import through the batch task framework (task 18 §4.3). */
@Service
public class RefundImportService {
  private final ImportTaskApi imports;
  private final ShopAccessApi shops;
  private final OrderWriter writer;
  private final OrderImportStateWriter importStates;
  private final OrderSyncFailureWriter syncFailures;

  public RefundImportService(
      ImportTaskApi imports,
      ShopAccessApi shops,
      OrderWriter writer,
      OrderImportStateWriter importStates,
      OrderSyncFailureWriter syncFailures) {
    this.imports = imports;
    this.shops = shops;
    this.writer = writer;
    this.importStates = importStates;
    this.syncFailures = syncFailures;
  }

  public byte[] template() {
    return imports.template(
        RefundImportColumn.headerKeys(), RefundImportColumn.textColumnIndexes());
  }

  /**
   * Submits the import with the mandatory data cutoff time (task 40, row 09.03). The task id latch
   * matches the order import: the processor needs the id for import-state and failure rows.
   */
  public Long submit(ImportFileDTO file, Instant dataCutoffTime) {
    AtomicReference<Long> taskId = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    Long id =
        imports.submitImport(
            TaskType.REFUND_IMPORT,
            RefundImportColumn.headerKeys(),
            List.of(),
            file,
            () -> {
              awaitTaskId(ready);
              return new RefundImportProcessor(
                  taskId.get(), shops, writer, importStates, syncFailures, dataCutoffTime);
            },
            TaskType.REFUND_IMPORT.maxRows(),
            OrderImportService.params(dataCutoffTime));
    taskId.set(id);
    ready.countDown();
    return id;
  }

  private static void awaitTaskId(CountDownLatch ready) {
    try {
      if (!ready.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Import task id was not published in time");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for import task id", e);
    }
  }
}
