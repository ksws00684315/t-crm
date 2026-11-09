package com.tcrm.admin.kollibrary.model;

/** Form of one published content (requirement 1.2.3: short video, live, image post). */
public enum LibraryContentForm implements LibraryOption {
  SHORT_VIDEO("短视频", "Short video"),
  LIVE("直播", "Live"),
  POST("图文", "Image post");

  private final String nameZh;
  private final String nameEn;

  LibraryContentForm(String nameZh, String nameEn) {
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
