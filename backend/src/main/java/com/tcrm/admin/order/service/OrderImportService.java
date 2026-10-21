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

/** Order Excel import through the batch task framework (task 18 §4.2). */
@Service
public class OrderImportService {
  private final ImportTaskApi imports;
  private final ShopAccessApi shops;
  private final OrderWriter writer;
  private final OrderImportStateWriter importStates;
  private final OrderSyncFailureWriter syncFailures;

  public OrderImportService(
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
    return imports.template(OrderImportColumn.headerKeys(), OrderImportColumn.textColumnIndexes());
  }

  /**
   * Submits the import with the mandatory data cutoff time (task 40, row 09.03): the operator
   * declares up to which moment the downloaded file's data reaches.
   */
  public Long submit(ImportFileDTO file, Instant dataCutoffTime) {
    AtomicReference<Long> taskId = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    Long id =
        imports.submitImport(
            TaskType.ORDER_IMPORT,
            OrderImportColumn.headerKeys(),
            List.of(),
            file,
            () -> {
              awaitTaskId(ready);
              return new OrderImportProcessor(
                  taskId.get(), shops, writer, importStates, syncFailures, dataCutoffTime);
            },
            TaskType.ORDER_IMPORT.maxRows(),
            params(dataCutoffTime));
    taskId.set(id);
    ready.countDown();
    return id;
  }

  static String params(Instant dataCutoffTime) {
    return "{\"dataCutoffTime\":\"" + dataCutoffTime + "\"}";
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
