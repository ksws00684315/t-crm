package com.tcrm.admin.task.api;

import java.util.List;

/**
 * One row-level failure for the error file: a message key (messages*.properties, "import.*") plus
 * interpolation arguments. The task framework renders it in the language the task was submitted in,
 * never in the worker thread's language.
 *
 * @param key message key; must exist in both language bundles
 * @param args MessageFormat arguments for the key, in order
 */
public record ImportReason(String key, List<Object> args) {
  public static ImportReason of(String key) {
    return new ImportReason(key, List.of());
  }

  public static ImportReason of(String key, Object... args) {
    return new ImportReason(key, List.of(args));
  }
}
