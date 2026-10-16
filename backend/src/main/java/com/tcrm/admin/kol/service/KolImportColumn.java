package com.tcrm.admin.kol.service;

import java.util.List;

/**
 * The KOL import template: column order and header message keys are defined here only (task 04
 * §4.5; task 11 §4.3 appended the four profile columns). The keys resolve to the Chinese and
 * English header texts in messages*.properties; the import accepts either full language, and files
 * with the pre-task-11 header (first {@link #LEGACY_COLUMNS} columns) keep working.
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
  REMARK("import.kol.header.remark"),
  WHATSAPP("import.kol.header.whatsapp"),
  TEMPLATE_CODE("import.kol.header.template-code"),
  INVITE_CODE("import.kol.header.invite-code"),
  SHEET_URL("import.kol.header.sheet-url");

  /** Columns of the template before task 11; files with exactly these are still accepted. */
  public static final int LEGACY_COLUMNS = 10;

  private static final List<KolImportColumn> VALUES = List.of(values());
  private static final List<String> HEADER_KEYS =
      VALUES.stream().map(KolImportColumn::headerKey).toList();
  private static final List<String> LEGACY_HEADER_KEYS =
      VALUES.subList(0, LEGACY_COLUMNS).stream().map(KolImportColumn::headerKey).toList();
  private final String headerKey;

  KolImportColumn(String headerKey) {
    this.headerKey = headerKey;
  }

  public String headerKey() {
    return headerKey;
  }

  /** Cell of this column in a row given in template order; "" when the file lacks the column. */
  public String of(List<String> row) {
    return ordinal() < row.size() ? row.get(ordinal()) : "";
  }

  public static List<String> headerKeys() {
    return HEADER_KEYS;
  }

  /** Header keys of the pre-task-11 template, for the accepted legacy file variant. */
  public static List<String> legacyHeaderKeys() {
    return LEGACY_HEADER_KEYS;
  }
}
