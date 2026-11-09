package com.tcrm.admin.kollibrary.model;

/** Outreach status of the creator (requirement 1.2.2; NOT_CONTACTED is the default). */
public enum LibraryOutreachStatus implements LibraryOption {
  NOT_CONTACTED("未建联", "Not contacted"),
  EMAIL_SENT("已发邮件", "Email sent"),
  REPLIED("已回复", "Replied"),
  WHATSAPP_ADDED("已加 WhatsApp", "WhatsApp added"),
  REJECTED("拒绝", "Rejected"),
  COOPERATING("合作中", "Cooperating");

  private final String nameZh;
  private final String nameEn;

  LibraryOutreachStatus(String nameZh, String nameEn) {
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
