package com.tcrm.admin.kollibrary.model;

/** Stage of the relationship with the creator (requirement 1.2.1, kept on the profile). */
public enum LibraryCooperationStage implements LibraryOption {
  FIRST_COOPERATION("初次合作", "First cooperation"),
  REPEAT_INVESTMENT("复投中", "Repeat investment"),
  LONG_TERM_PARTNERSHIP("长期深度合作", "Long-term partnership"),
  EXCLUSIVE("独家", "Exclusive");

  private final String nameZh;
  private final String nameEn;

  LibraryCooperationStage(String nameZh, String nameEn) {
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
