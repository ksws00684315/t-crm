package com.tcrm.admin.task.api;

/** Batch task types; the database CHECK constraint lists only implemented values. */
public enum TaskType {
  /** Excel import of KOLs (task 04); row limit 2,000. */
  KOL_IMPORT(2_000),
  /** Excel import of original products (task 05); row limit 20,000. */
  PRODUCT_IMPORT(20_000),
  /** Excel import of kol item mappings (task 05); row limit 20,000. */
  MAPPING_IMPORT(20_000),
  /** Excel import of orders (task 18); row limit 20,000. */
  ORDER_IMPORT(20_000),
  /** Excel import of completed refunds (task 18); row limit 20,000. */
  REFUND_IMPORT(20_000);

  private final int maxRows;

  TaskType(int maxRows) {
    this.maxRows = maxRows;
  }

  /** Maximum data rows accepted for this task type (file size limit is still 20 MB). */
  public int maxRows() {
    return maxRows;
  }
}
