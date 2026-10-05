package com.tcrm.admin.kol.service;

import java.util.Arrays;
import java.util.List;

/**
 * The KOL import template: column order and header texts are defined here only (task 04 §4.5; may
 * change after M0 confirmation).
 */
public enum KolImportColumn {
  CODE("达人编号"),
  NAME("姓名"),
  EMAIL("邮箱"),
  COUNTRY("国家"),
  BD_USERNAME("负责商务用户名"),
  DEFAULT_RATE("默认佣金比例(%)"),
  PLATFORM("社媒平台"),
  HANDLE("社媒账号"),
  FOLLOWERS("粉丝数"),
  REMARK("备注");

  private static final List<String> HEADERS =
      Arrays.stream(values()).map(KolImportColumn::header).toList();
  private final String header;

  KolImportColumn(String header) {
    this.header = header;
  }

  public String header() {
    return header;
  }

  /** Cell of this column in a row given in template order. */
  public String of(List<String> row) {
    return row.get(ordinal());
  }

  public static List<String> headers() {
    return HEADERS;
  }
}
