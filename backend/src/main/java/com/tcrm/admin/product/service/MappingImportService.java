package com.tcrm.admin.product.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Kol item mapping Excel import through the batch task framework. */
@Service
public class MappingImportService {
  private final ImportTaskApi imports;
  private final CurrentUserContext current;
  private final ShopService shops;
  private final ProductService products;
  private final KolItemMapper items;
  private final ProductMapper productMapper;
  private final KolApi kols;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  public MappingImportService(
      ImportTaskApi imports,
      CurrentUserContext current,
      ShopService shops,
      ProductService products,
      KolItemMapper items,
      ProductMapper productMapper,
      KolApi kols,
      TransactionTemplate tx,
      ApplicationEventPublisher events) {
    this.imports = imports;
    this.current = current;
    this.shops = shops;
    this.products = products;
    this.items = items;
    this.productMapper = productMapper;
    this.kols = kols;
    this.tx = tx;
    this.events = events;
  }

  public byte[] template() {
    return imports.template(
        MappingImportColumn.headerKeys(), MappingImportColumn.textColumnIndexes());
  }

  public Long submit(ImportFileDTO file) {
    Long actorId = current.id();
    return imports.submitImport(
        TaskType.MAPPING_IMPORT,
        MappingImportColumn.headerKeys(),
        file,
        () ->
            new MappingImportProcessor(
                actorId, shops, products, items, productMapper, kols, tx, events),
        TaskType.MAPPING_IMPORT.maxRows());
  }
}
