package com.tcrm.admin.kollibrary.model;

/** Audience gender split (requirement 1.2.6 ①). */
public enum LibraryAudienceGender implements LibraryOption {
  MOSTLY_FEMALE("女性为主", "Mostly female"),
  MOSTLY_MALE("男性为主", "Mostly male"),
  BALANCED("均衡", "Balanced");

  private final String nameZh;
  private final String nameEn;

  LibraryAudienceGender(String nameZh, String nameEn) {
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
