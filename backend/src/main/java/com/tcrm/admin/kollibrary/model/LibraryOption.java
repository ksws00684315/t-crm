package com.tcrm.admin.kollibrary.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A selectable value of the creator library (task 143 §3.1.3): the API stores the English constant,
 * the screen shows the Chinese or English name from this registry. Every dropdown value of the
 * library lives in one of the option enums of this package, so the customer review of requirement
 * Q-2 has a single place to check.
 */
public interface LibraryOption {
  /** Chinese name shown to the operator. */
  String nameZh();

  /** English name. */
  String nameEn();

  /** The option with this API value, or empty; a blank value is empty. */
  static <E extends Enum<E> & LibraryOption> Optional<E> of(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(value)).findFirst();
  }

  /** All values of the enum in declaration order. */
  static <E extends Enum<E> & LibraryOption> List<String> values(Class<E> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
  }
}
