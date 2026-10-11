package com.tcrm.admin.kol.service;

import java.util.Arrays;
import java.util.List;

/**
 * The KOL import template: column order and header message keys are defined here only (task 04
 * §4.5; may change after M0 confirmation). The keys resolve to the Chinese and English header texts
 * in messages*.properties; the import accepts either full language.
 */
public enum KolImportColumn {
  CODE("import.kol.header.code"),
  NAME("import.kol.header.name"),
  EMAIL("import.kol.header.email"),
  COUNTRY("import.kol.header.country"),
  BD_USERNAME("import.kol.header.bd-username"),
  DEFAULT_RATE("import.kol.header.default-rate"),
  PLATFORM("import.kol.header.platform"),
  HANDLE("import.kol.header.handle"),
  FOLLOWERS("import.kol.header.followers"),
  REMARK("import.kol.header.remark");

  private static final List<String> HEADER_KEYS =
      Arrays.stream(values()).map(KolImportColumn::headerKey).toList();
  private final String headerKey;

  KolImportColumn(String headerKey) {
    this.headerKey = headerKey;
  }

  public String headerKey() {
    return headerKey;
  }

  /** Cell of this column in a row given in template order. */
  public String of(List<String> row) {
    return row.get(ordinal());
  }

  public static List<String> headerKeys() {
    return HEADER_KEYS;
  }
}
