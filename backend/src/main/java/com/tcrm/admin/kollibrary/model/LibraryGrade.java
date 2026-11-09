package com.tcrm.admin.kollibrary.model;

/**
 * Creator grade, rated by hand in phase 1 (requirement 1.2.6 ①; automatic grading is phase 2
 * P2-02).
 */
public enum LibraryGrade implements LibraryOption {
  S("S", "S"),
  A("A", "A"),
  B("B", "B"),
  C("C", "C");

  private final String nameZh;
  private final String nameEn;

  LibraryGrade(String nameZh, String nameEn) {
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
