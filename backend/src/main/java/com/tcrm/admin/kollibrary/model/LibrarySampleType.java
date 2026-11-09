package com.tcrm.admin.kollibrary.model;

/** What the sample was for (requirement 1.2.3: gift or paid photo shoot). */
public enum LibrarySampleType implements LibraryOption {
  GIFT("送样", "Gift"),
  PHOTO_SHOOT("寄拍", "Photo shoot");

  private final String nameZh;
  private final String nameEn;

  LibrarySampleType(String nameZh, String nameEn) {
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
