package com.tcrm.admin.kollibrary.model;

/** Sample status of the creator (requirement 1.2.2; NOT_SENT is the default). */
public enum LibrarySampleStatus implements LibraryOption {
  NOT_SENT("未寄样", "Not sent"),
  SHIPPED("已寄出", "Shipped"),
  RECEIVED("已签收", "Received"),
  NOT_NEEDED("无需寄样", "Not needed");

  private final String nameZh;
  private final String nameEn;

  LibrarySampleStatus(String nameZh, String nameEn) {
    this.nameZh = nameZh;
    this.nameEn = nameEn;
  }

  @Override
  public String nameZh() {
    return nameZh;
  }

  @Override
  public String nameEn() {
    return nameEn;
  }
}
