package com.tcrm.admin.order.service;

import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
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

  public OrderImportService(ImportTaskApi imports, ShopAccessApi shops, OrderWriter writer) {
    this.imports = imports;
    this.shops = shops;
    this.writer = writer;
  }

  public byte[] template() {
    return imports.template(OrderImportColumn.headerKeys(), OrderImportColumn.textColumnIndexes());
  }

  public Long submit(ImportFileDTO file) {
    AtomicReference<Long> taskId = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    Long id =
        imports.submitImport(
            TaskType.ORDER_IMPORT,
            OrderImportColumn.headerKeys(),
            file,
            () -> {
              awaitTaskId(ready);
              return new OrderImportProcessor(taskId.get(), shops, writer);
            },
            TaskType.ORDER_IMPORT.maxRows());
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
