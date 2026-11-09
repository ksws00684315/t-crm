package com.tcrm.admin.kollibrary.model;

/**
 * High / medium / low ratings of content quality, selling potential and cooperation attitude
 * (requirement 1.2.6 ①).
 */
public enum LibraryLevel implements LibraryOption {
  HIGH("高", "High"),
  MEDIUM("中", "Medium"),
  LOW("低", "Low");

  private final String nameZh;
  private final String nameEn;

  LibraryLevel(String nameZh, String nameEn) {
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
