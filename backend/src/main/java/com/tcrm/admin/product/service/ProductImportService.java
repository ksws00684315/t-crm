package com.tcrm.admin.product.service;

import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Original-product Excel import through the batch task framework. */
@Service
public class ProductImportService {
  private final ImportTaskApi imports;
  private final CurrentUserContext current;
  private final ShopService shops;
  private final ProductMapper products;
  private final KolItemMapper kolItems;
  private final ProductCodeGenerator codes;
  private final TransactionTemplate tx;

  public ProductImportService(
      ImportTaskApi imports,
      CurrentUserContext current,
      ShopService shops,
      ProductMapper products,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      TransactionTemplate tx) {
    this.imports = imports;
    this.current = current;
    this.shops = shops;
    this.products = products;
    this.kolItems = kolItems;
    this.codes = codes;
    this.tx = tx;
  }

  public byte[] template() {
    return imports.template(ProductImportColumn.headerKeys());
  }

  public Long submit(ImportFileDTO file) {
    Long actorId = current.id();
    return imports.submitImport(
        TaskType.PRODUCT_IMPORT,
        ProductImportColumn.headerKeys(),
        file,
        () -> new ProductImportProcessor(actorId, null, shops, products, kolItems, codes, tx),
        TaskType.PRODUCT_IMPORT.maxRows());
  }
}
