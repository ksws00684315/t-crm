package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** Product source sheet configuration and last read summary (ADR-021). */
@TableName("crm_sheet_source")
public class SheetSourceDO extends BaseEntity {
  private String sourceName;

  private String spreadsheetId;

  private String sheetTitle;

  private Integer headerRow;

  private String linkColumn;

  private String nameColumn;

  private String priceColumn;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String categoryColumn;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String imageColumn;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long defaultShopId;

  private String priceCurrency;

  private Integer status;

  private Integer readNo;

  private Instant lastReadAt;

  private Long lastTaskId;

  private Integer lastRowCount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastSkipped;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastError;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public String getSourceName() {
    return sourceName;
  }

  public void setSourceName(String value) {
    sourceName = value;
  }

  public String getSpreadsheetId() {
    return spreadsheetId;
  }

  public void setSpreadsheetId(String value) {
    spreadsheetId = value;
  }

  public String getSheetTitle() {
    return sheetTitle;
  }

  public void setSheetTitle(String value) {
    sheetTitle = value;
  }

  public Integer getHeaderRow() {
    return headerRow;
  }

  public void setHeaderRow(Integer value) {
    headerRow = value;
  }

  public String getLinkColumn() {
    return linkColumn;
  }

  public void setLinkColumn(String value) {
    linkColumn = value;
  }

  public String getNameColumn() {
    return nameColumn;
  }

  public void setNameColumn(String value) {
    nameColumn = value;
  }

  public String getPriceColumn() {
    return priceColumn;
  }

  public void setPriceColumn(String value) {
    priceColumn = value;
  }

  public String getCategoryColumn() {
    return categoryColumn;
  }

  public void setCategoryColumn(String value) {
    categoryColumn = value;
  }

  public String getImageColumn() {
    return imageColumn;
  }

  public void setImageColumn(String value) {
    imageColumn = value;
  }

  public Long getDefaultShopId() {
    return defaultShopId;
  }

  public void setDefaultShopId(Long value) {
    defaultShopId = value;
  }

  public String getPriceCurrency() {
    return priceCurrency;
  }

  public void setPriceCurrency(String value) {
    priceCurrency = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    status = value;
  }

  public Integer getReadNo() {
    return readNo;
  }

  public void setReadNo(Integer value) {
    readNo = value;
  }

  public Instant getLastReadAt() {
    return lastReadAt;
  }

  public void setLastReadAt(Instant value) {
    lastReadAt = value;
  }

  public Long getLastTaskId() {
    return lastTaskId;
  }

  public void setLastTaskId(Long value) {
    lastTaskId = value;
  }

  public Integer getLastRowCount() {
    return lastRowCount;
  }

  public void setLastRowCount(Integer value) {
    lastRowCount = value;
  }

  public String getLastSkipped() {
    return lastSkipped;
  }

  public void setLastSkipped(String value) {
    lastSkipped = value;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    lastError = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
