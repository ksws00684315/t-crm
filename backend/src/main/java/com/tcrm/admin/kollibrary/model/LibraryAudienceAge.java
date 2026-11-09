package com.tcrm.admin.kollibrary.model;

/** Audience age band, mirroring the TikTok audience analysis (requirement 1.2.6 ①). */
public enum LibraryAudienceAge implements LibraryOption {
  AGE_18_24("18~24", "18 to 24"),
  AGE_25_34("25~34", "25 to 34"),
  AGE_35_44("35~44", "35 to 44"),
  AGE_45_54("45~54", "45 to 54"),
  AGE_55_PLUS("55+", "55+");

  private final String nameZh;
  private final String nameEn;

  LibraryAudienceAge(String nameZh, String nameEn) {
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
