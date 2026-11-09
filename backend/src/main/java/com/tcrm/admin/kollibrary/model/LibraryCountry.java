package com.tcrm.admin.kollibrary.model;

/**
 * Country site (requirement 1.2.6 ①: United States default, then the other TikTok Shop sites and
 * {@code OTHER}). Stored as the site code, which is also the ISO 3166-1 alpha-2 code except for
 * {@code OTHER}; the customer confirms the final list (Q-2).
 */
public enum LibraryCountry implements LibraryOption {
  US("美国", "United States"),
  GB("英国", "United Kingdom"),
  MX("墨西哥", "Mexico"),
  DE("德国", "Germany"),
  FR("法国", "France"),
  IT("意大利", "Italy"),
  ES("西班牙", "Spain"),
  JP("日本", "Japan"),
  ID("印尼", "Indonesia"),
  TH("泰国", "Thailand"),
  VN("越南", "Vietnam"),
  MY("马来西亚", "Malaysia"),
  PH("菲律宾", "Philippines"),
  SG("新加坡", "Singapore"),
  OTHER("其他", "Other");

  private final String nameZh;
  private final String nameEn;

  LibraryCountry(String nameZh, String nameEn) {
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
