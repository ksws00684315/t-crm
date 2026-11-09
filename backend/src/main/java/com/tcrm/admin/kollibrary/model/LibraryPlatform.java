package com.tcrm.admin.kollibrary.model;

/**
 * Platform the creator works on (requirement 1.2.6 ①: TikTok default, Instagram, YouTube, other).
 */
public enum LibraryPlatform implements LibraryOption {
  TIKTOK("TikTok", "TikTok"),
  INSTAGRAM("Instagram", "Instagram"),
  YOUTUBE("YouTube", "YouTube"),
  OTHER("其他", "Other");

  private final String nameZh;
  private final String nameEn;

  LibraryPlatform(String nameZh, String nameEn) {
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
