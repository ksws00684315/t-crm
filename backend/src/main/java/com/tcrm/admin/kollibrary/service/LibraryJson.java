package com.tcrm.admin.kollibrary.service;

import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Storage format of the library's JSONB arrays ({@code categories} of a creator, {@code tags} of a
 * creator). Its own mapper keeps the text stable, independent of the API mapper's settings.
 */
final class LibraryJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

  private LibraryJson() {}

  static String write(List<String> values) {
    return JSON.writeValueAsString(values == null ? List.of() : values);
  }

  static List<String> read(String json) {
    return json == null || json.isBlank() ? List.of() : List.copyOf(JSON.readValue(json, TYPE));
  }
}
